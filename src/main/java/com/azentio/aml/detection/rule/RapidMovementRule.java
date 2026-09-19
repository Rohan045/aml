package com.azentio.aml.detection.rule;

import com.azentio.aml.common.Format;
import com.azentio.aml.common.Json;
import com.azentio.aml.detection.DetectionContext;
import com.azentio.aml.detection.RuleFinding;
import com.azentio.aml.detection.RuleParameters;
import com.azentio.aml.domain.RuleConfig;
import com.azentio.aml.domain.Transaction;
import com.azentio.aml.domain.enums.AmlTypology;
import com.azentio.aml.domain.enums.TransactionDirection;
import com.azentio.aml.repository.TransactionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Business rule 3 - funds arrive and at least 80% of the value leaves again within 48 hours
 * (layering / pass-through).
 *
 * <p>The ratio is measured per inflow, not per calendar window. An account that receives 100,000 on
 * Monday and pays out 85,000 on Tuesday is laundering-shaped; the same account receiving and paying
 * comparable totals spread over an unrelated fortnight is ordinary business. Anchoring each
 * measurement on a specific deposit and looking only forward from it is what separates the two, and
 * it is also what lets the alert name the exact deposit that was passed through.
 *
 * <p>Only the largest qualifying inflow is reported per account per sweep, so a busy pass-through
 * account produces one explainable alert rather than one per deposit.
 */
@Component
public class RapidMovementRule extends AbstractDetectionRule {

    private static final Logger log = LoggerFactory.getLogger(RapidMovementRule.class);
    private static final String RULE_CODE = "RAPID_MOVEMENT_48H";
    private static final BigDecimal DEFAULT_MIN_INFLOW = new BigDecimal("1000.00");
    private static final BigDecimal DEFAULT_RATIO = new BigDecimal("0.80");
    private static final int DEFAULT_WINDOW_HOURS = 48;

    private final TransactionRepository transactionRepository;

    public RapidMovementRule(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    @Override
    public String ruleCode() {
        return RULE_CODE;
    }

    @Override
    public AmlTypology typology() {
        return AmlTypology.RAPID_MOVEMENT;
    }

    @Override
    public List<RuleFinding> evaluate(RuleConfig config, DetectionContext context) {
        BigDecimal minInflow = threshold(config, DEFAULT_MIN_INFLOW);
        BigDecimal ratioThreshold =
                config.getRatioThreshold() == null ? DEFAULT_RATIO : config.getRatioThreshold();
        Duration window = ruleWindow(config, DEFAULT_WINDOW_HOURS);
        RuleParameters parameters = RuleParameters.of(config);
        List<String> outflowTypes = parameters.strings("outflowTypes");

        Instant from = queryStart(config, context, DEFAULT_WINDOW_HOURS);
        Instant to = context.windowEnd();

        List<String> accounts =
                context.isStreaming()
                        ? List.of(accountIdOf(context.trigger()))
                        : transactionRepository.findAccountsWithSignificantInflow(
                                from, to, minInflow);

        List<RuleFinding> findings = new ArrayList<>();
        for (String accountId : accounts) {
            // The outflow leg may land after the sweep's end, so the fetch extends a full window
            // beyond it; otherwise a deposit near the boundary would look like it was never moved.
            List<Transaction> activity =
                    transactionRepository.findAccountWindow(accountId, from, to.plus(window));
            passThrough(activity, minInflow, ratioThreshold, window, outflowTypes)
                    .ifPresent(
                            match ->
                                    findings.add(
                                            toFinding(
                                                    config,
                                                    context,
                                                    accountId,
                                                    match,
                                                    ratioThreshold,
                                                    window,
                                                    minInflow)));
        }
        return findings;
    }

    /** Best (highest-value) deposit whose value was substantially moved out inside the window. */
    private java.util.Optional<PassThrough> passThrough(
            List<Transaction> activity,
            BigDecimal minInflow,
            BigDecimal ratioThreshold,
            Duration window,
            List<String> outflowTypes) {
        PassThrough best = null;
        for (Transaction inflow : activity) {
            if (inflow.getDirection() != TransactionDirection.CREDIT
                    || inflow.getBaseAmount().compareTo(minInflow) < 0) {
                continue;
            }
            Instant deadline = inflow.getTransactionTimestamp().plus(window);
            List<Transaction> outflows = new ArrayList<>();
            BigDecimal movedOut = BigDecimal.ZERO;
            for (Transaction candidate : activity) {
                if (candidate.getDirection() != TransactionDirection.DEBIT) {
                    continue;
                }
                Instant at = candidate.getTransactionTimestamp();
                if (at.isBefore(inflow.getTransactionTimestamp()) || at.isAfter(deadline)) {
                    continue;
                }
                if (!outflowTypes.isEmpty()
                        && !outflowTypes.contains(candidate.getTransactionType().name())) {
                    continue;
                }
                outflows.add(candidate);
                movedOut = movedOut.add(candidate.getBaseAmount());
            }
            if (outflows.isEmpty()) {
                continue;
            }
            // Rounded down, never half-up. Half-up rounding would lift a ratio of
            // 0.79999 to 0.8000 and raise an alert against a customer who did not
            // actually cross the configured threshold - and an alert the evidence
            // cannot justify is worse than no alert at all.
            BigDecimal ratio = movedOut.divide(inflow.getBaseAmount(), 4, RoundingMode.DOWN);
            if (ratio.compareTo(ratioThreshold) < 0) {
                continue;
            }
            PassThrough candidate = new PassThrough(inflow, outflows, movedOut, ratio);
            if (best == null
                    || candidate.inflow().getBaseAmount().compareTo(best.inflow().getBaseAmount())
                            > 0) {
                best = candidate;
            }
        }
        return java.util.Optional.ofNullable(best);
    }

    private RuleFinding toFinding(
            RuleConfig config,
            DetectionContext context,
            String accountId,
            PassThrough match,
            BigDecimal ratioThreshold,
            Duration window,
            BigDecimal minInflow) {
        Transaction inflow = match.inflow();
        String currency = inflow.getBaseCurrency();
        Transaction lastOutflow = match.outflows().get(match.outflows().size() - 1);
        Duration elapsed =
                Duration.between(
                        inflow.getTransactionTimestamp(), lastOutflow.getTransactionTimestamp());

        String explanation =
                ("Account %s received %s on %s and moved %s (%s of the deposit) back out across %d "
                                + "transactions, the last of them %d hours %d minutes later - "
                                + "within the %d-hour layering window. The rule fires when %s or "
                                + "more of a deposit of at least %s leaves again this quickly, "
                                + "because funds that pass straight through an account are being "
                                + "layered rather than held or spent.")
                        .formatted(
                                accountId,
                                Format.money(inflow.getBaseAmount(), currency),
                                Format.timestamp(inflow.getTransactionTimestamp()),
                                Format.money(match.movedOut(), currency),
                                Format.percent(match.ratio()),
                                match.outflows().size(),
                                elapsed.toHours(),
                                elapsed.toMinutesPart(),
                                window.toHours(),
                                Format.percent(ratioThreshold),
                                Format.money(minInflow, currency));

        RuleFinding.RuleFindingBuilder builder =
                RuleFinding.builder()
                        .ruleCode(RULE_CODE)
                        .ruleName(config.getRuleName())
                        .ruleVersion(config.getConfigVersion())
                        .typology(typology())
                        .severity(config.getSeverity())
                        .ruleWeight(weightOf(config))
                        .customerId(customerIdOf(inflow))
                        .accountId(accountId)
                        .discriminator(accountId)
                        .title(
                                "%s of a %s deposit moved out within %dh"
                                        .formatted(
                                                Format.percent(match.ratio()),
                                                Format.money(inflow.getBaseAmount(), currency),
                                                window.toHours()))
                        .explanation(explanation)
                        .detailsJson(
                                Json.of(
                                        "inflowBaseAmount", inflow.getBaseAmount(),
                                        "outflowBaseAmount", match.movedOut(),
                                        "observedRatio", match.ratio(),
                                        "ratioThreshold", ratioThreshold,
                                        "windowHours", window.toHours(),
                                        "elapsedMinutes", elapsed.toMinutes(),
                                        "minimumInflow", minInflow,
                                        "outflowLegs", match.outflows().size(),
                                        "inflowTxnId", inflow.getExternalTxnId()))
                        .windowStart(inflow.getTransactionTimestamp())
                        .windowEnd(lastOutflow.getTransactionTimestamp())
                        .detectedAt(context.evaluatedAt())
                        .totalAmount(match.movedOut())
                        .transactionCount(match.outflows().size() + 1)
                        .evidence(RuleFinding.EvidenceItem.of(inflow, "INFLOW", "Deposit under review"));

        for (Transaction outflow : match.outflows()) {
            builder.evidence(RuleFinding.EvidenceItem.of(outflow, "OUTFLOW"));
        }
        log.debug("{}: account {} passed through {}", RULE_CODE, accountId, match.ratio());
        return builder.build();
    }

    private record PassThrough(
            Transaction inflow,
            List<Transaction> outflows,
            BigDecimal movedOut,
            BigDecimal ratio) {}
}
