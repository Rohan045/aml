package com.azentio.aml.detection;

import com.azentio.aml.config.SentinelProperties;
import com.azentio.aml.domain.RuleConfig;
import com.azentio.aml.domain.Transaction;
import com.azentio.aml.domain.enums.ScreeningStatus;
import com.azentio.aml.repository.TransactionRepository;
import com.azentio.aml.service.AlertService;
import com.azentio.aml.service.RuleConfigService;
import com.azentio.aml.service.WatchlistService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates the detection rules.
 *
 * <p>Rules are discovered as Spring beans and matched to their {@code rule_configs} row by code, so
 * the engine itself never needs to know which typologies exist: adding one means adding a
 * {@link DetectionRule} bean and a configuration row. Configuration is read at evaluation time, not
 * at startup, which is what lets compliance re-tune or disable a live rule without a redeployment.
 *
 * <p>Two entry points:
 *
 * <ul>
 *   <li>{@link #screen(Transaction)} - the streaming path, evaluated against windows anchored on the
 *       arriving transaction. Every query is account- or customer-scoped and index-backed, which is
 *       what keeps latency independent of the size of the book.
 *   <li>{@link #sweep(Instant, Instant)} - the bulk path over a time window, used after a CSV load
 *       and by the scheduled re-scan.
 * </ul>
 *
 * <p>A rule that throws is logged and skipped rather than being allowed to abort the pass: one
 * misconfigured rule must not stop the other seven from protecting the bank.
 */
@Service
public class DetectionEngine {

    private static final Logger log = LoggerFactory.getLogger(DetectionEngine.class);

    private final Map<String, DetectionRule> rulesByCode = new HashMap<>();
    private final RuleConfigService ruleConfigService;
    private final WatchlistService watchlistService;
    private final AlertService alertService;
    private final TransactionRepository transactionRepository;
    private final SentinelProperties properties;

    public DetectionEngine(
            List<DetectionRule> rules,
            RuleConfigService ruleConfigService,
            WatchlistService watchlistService,
            AlertService alertService,
            TransactionRepository transactionRepository,
            SentinelProperties properties) {
        rules.forEach(rule -> this.rulesByCode.put(rule.ruleCode(), rule));
        this.ruleConfigService = ruleConfigService;
        this.watchlistService = watchlistService;
        this.alertService = alertService;
        this.transactionRepository = transactionRepository;
        this.properties = properties;
        log.info("Detection engine initialised with rules {}", rulesByCode.keySet());
    }

    // ------------------------------------------------------------------
    // Streaming path
    // ------------------------------------------------------------------

    /**
     * Evaluates every streaming-capable rule against a single newly arrived transaction.
     *
     * <p>Each rule receives a window anchored on the transaction and reaching back by that rule's
     * own configured lookback, so a 24-hour structuring check and a 48-hour layering check both see
     * exactly the history they need and nothing more.
     */
    @Transactional
    public AlertService.Outcome screen(Transaction transaction) {
        Instant evaluatedAt = Instant.now();
        WatchlistService.Snapshot watchlist = watchlistService.snapshot(evaluatedAt);
        List<RuleConfig> configs = ruleConfigService.activeRules(evaluatedAt);

        AlertService.Outcome outcome = AlertService.Outcome.EMPTY;
        for (RuleConfig config : configs) {
            DetectionRule rule = rulesByCode.get(config.getRuleCode());
            if (rule == null || !rule.supportsStreaming()) {
                continue;
            }
            Duration lookback = lookbackFor(config);
            DetectionContext context =
                    DetectionContext.forTransaction(
                            transaction,
                            transaction.getTransactionTimestamp().minus(lookback),
                            watchlist);
            List<RuleFinding> findings = evaluateSafely(rule, config, context);
            outcome = outcome.plus(alertService.raise(findings, dedupeWindow(config)));
        }

        transaction.setScreeningStatus(ScreeningStatus.SCREENED);
        transaction.setScreenedAt(evaluatedAt);
        transactionRepository.save(transaction);
        return outcome;
    }

    // ------------------------------------------------------------------
    // Bulk path
    // ------------------------------------------------------------------

    /**
     * Evaluates every active rule over {@code [from, to)}.
     *
     * <p>The window is normally wider than any individual rule's, which is intentional: the rules
     * use it to select candidates cheaply in the database and then confirm their own exact rolling
     * window in memory.
     */
    @Transactional
    public AlertService.Outcome sweep(Instant from, Instant to) {
        if (!from.isBefore(to)) {
            throw new IllegalArgumentException("Sweep window start must precede its end");
        }
        Instant evaluatedAt = Instant.now();
        WatchlistService.Snapshot watchlist = watchlistService.snapshot(evaluatedAt);
        List<RuleConfig> configs = ruleConfigService.activeRules(evaluatedAt);
        DetectionContext context = DetectionContext.forWindow(from, to, watchlist);

        log.info("Detection sweep over [{} .. {}) with {} active rules", from, to, configs.size());
        AlertService.Outcome outcome = AlertService.Outcome.EMPTY;
        for (RuleConfig config : configs) {
            DetectionRule rule = rulesByCode.get(config.getRuleCode());
            if (rule == null) {
                log.warn(
                        "Rule configuration {} has no implementation bean; skipping",
                        config.getRuleCode());
                continue;
            }
            long started = System.currentTimeMillis();
            List<RuleFinding> findings = evaluateSafely(rule, config, context);
            outcome = outcome.plus(alertService.raise(findings, dedupeWindow(config)));
            log.debug(
                    "Rule {} produced {} findings in {} ms",
                    config.getRuleCode(),
                    findings.size(),
                    System.currentTimeMillis() - started);
        }
        log.info(
                "Sweep complete: {} alerts created, {} aggregated",
                outcome.created(),
                outcome.aggregated());
        return outcome;
    }

    /**
     * Screens everything still queued as {@link ScreeningStatus#PENDING}.
     *
     * <p>Used after a bulk load. The sweep is anchored on the ingested data's own time range rather
     * than on "now", so a file of historical transactions is evaluated against the periods it
     * actually describes instead of being compared against an empty recent window.
     */
    @Transactional
    public AlertService.Outcome screenPending() {
        int pageSize = properties.getDetection().getScreeningPageSize();
        Page<Transaction> page =
                transactionRepository.findByScreeningStatusOrderByTransactionTimestampAsc(
                        ScreeningStatus.PENDING, PageRequest.of(0, pageSize));
        if (page.isEmpty()) {
            return AlertService.Outcome.EMPTY;
        }

        List<Transaction> pending = page.getContent();
        Instant earliest = pending.get(0).getTransactionTimestamp();
        Instant latest = pending.get(pending.size() - 1).getTransactionTimestamp();
        // The window must extend past the newest transaction because the repository windows are
        // half-open; without this the final transaction would never be evaluated.
        AlertService.Outcome outcome = sweep(earliest, latest.plusMillis(1));

        List<Long> ids = new ArrayList<>(pending.size());
        pending.forEach(transaction -> ids.add(transaction.getId()));
        transactionRepository.markScreened(ids, ScreeningStatus.SCREENED, Instant.now());
        log.info("Screened {} pending transactions", ids.size());
        return outcome;
    }

    /** Periodic re-scan, so late-arriving or back-dated records are not missed. */
    @Scheduled(
            fixedDelayString = "${sentinel.detection.sweep-interval-ms:900000}",
            initialDelayString = "${sentinel.detection.sweep-initial-delay-ms:60000}")
    public void scheduledSweep() {
        if (!properties.getDetection().isScheduledSweepEnabled()) {
            return;
        }
        try {
            screenPending();
        } catch (RuntimeException ex) {
            log.error("Scheduled detection sweep failed", ex);
        }
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /**
     * Isolates rule failures. A rule that throws - bad configuration, unexpected data - is logged
     * and skipped so the rest of the pass still runs.
     */
    private List<RuleFinding> evaluateSafely(
            DetectionRule rule, RuleConfig config, DetectionContext context) {
        try {
            List<RuleFinding> findings = rule.evaluate(config, context);
            return findings == null ? List.of() : findings;
        } catch (RuntimeException ex) {
            log.error(
                    "Rule {} (version {}) failed and was skipped for this pass",
                    config.getRuleCode(),
                    config.getConfigVersion(),
                    ex);
            return List.of();
        }
    }

    /** How far back a rule needs to see, falling back to its dedupe window then to 24 hours. */
    private Duration lookbackFor(RuleConfig config) {
        if (config.getTimeWindowHours() != null && config.getTimeWindowHours() > 0) {
            return Duration.ofHours(config.getTimeWindowHours());
        }
        if (config.getLookbackDays() != null && config.getLookbackDays() > 0) {
            return Duration.ofDays(config.getLookbackDays());
        }
        return Duration.ofHours(24);
    }

    private int dedupeWindow(RuleConfig config) {
        Integer hours = config.getDedupeWindowHours();
        return hours == null || hours < 1 ? 24 : hours;
    }

    /** Exposed for diagnostics and the admin API. */
    public List<String> registeredRuleCodes() {
        return rulesByCode.keySet().stream().sorted().toList();
    }
}
