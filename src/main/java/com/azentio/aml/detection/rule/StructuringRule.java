package com.azentio.aml.detection.rule;

import com.azentio.aml.common.Format;
import com.azentio.aml.common.Json;
import com.azentio.aml.detection.DetectionContext;
import com.azentio.aml.detection.RollingWindow;
import com.azentio.aml.detection.RuleFinding;
import com.azentio.aml.domain.RuleConfig;
import com.azentio.aml.domain.Transaction;
import com.azentio.aml.domain.enums.AmlTypology;
import com.azentio.aml.repository.TransactionRepository;
import com.azentio.aml.repository.projection.AccountActivitySummary;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Business rule 2 - three or more transactions on one account inside a rolling 24-hour window, each
 * individually falling in the USD 9,000-9,999.99 band, indicate deliberate structuring below the
 * reporting threshold.
 *
 * <p>Evaluation is two-phase. The database groups the sweep interval by account and returns only
 * accounts that already meet the count condition; the exact rolling window is then confirmed in
 * memory over that account's few qualifying rows. Without the second phase a 48-hour sweep would
 * report three transactions spread across two days as a 24-hour structuring pattern, which is a
 * false positive an analyst would (rightly) reject.
 */
@Component
public class StructuringRule extends AbstractDetectionRule {

    private static final Logger log = LoggerFactory.getLogger(StructuringRule.class);
    private static final String RULE_CODE = "STRUCTURING_24H";
    private static final BigDecimal DEFAULT_MAX = new BigDecimal("9999.99");
    private static final BigDecimal DEFAULT_MIN = new BigDecimal("9000.00");
    private static final int DEFAULT_WINDOW_HOURS = 24;
    private static final int DEFAULT_MIN_OCCURRENCES = 3;

    private final TransactionRepository transactionRepository;

    public StructuringRule(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    @Override
    public String ruleCode() {
        return RULE_CODE;
    }

    @Override
    public AmlTypology typology() {
        return AmlTypology.STRUCTURING;
    }

    @Override
    public List<RuleFinding> evaluate(RuleConfig config, DetectionContext context) {
        BigDecimal maxAmount = threshold(config, DEFAULT_MAX);
        BigDecimal minAmount =
                config.getThresholdAmountMin() == null
                        ? DEFAULT_MIN
                        : config.getThresholdAmountMin();
        int minOccurrences = minOccurrences(config, DEFAULT_MIN_OCCURRENCES);
        Duration window = ruleWindow(config, DEFAULT_WINDOW_HOURS);
        Instant from = queryStart(config, context, DEFAULT_WINDOW_HOURS);
        Instant to = context.windowEnd();

        List<String> candidateAccounts =
                candidateAccounts(context, from, to, minAmount, maxAmount, minOccurrences);

        List<RuleFinding> findings = new ArrayList<>();
        for (String accountId : candidateAccounts) {
            List<Transaction> band =
                    transactionRepository.findStructuringEvidence(
                            accountId, from, to, minAmount, maxAmount);
            Optional<List<Transaction>> confirmed =
                    RollingWindow.densestWindow(band, window, minOccurrences);
            if (confirmed.isEmpty()) {
                log.debug(
                        "{}: account {} met the count over the sweep but not within {}h",
                        RULE_CODE,
                        accountId,
                        window.toHours());
                continue;
            }
            findings.add(
                    toFinding(
                            config,
                            context,
                            accountId,
                            confirmed.get(),
                            minAmount,
                            maxAmount,
                            minOccurrences,
                            window));
        }
        return findings;
    }

    /**
     * In streaming mode the arriving transaction must itself be in the band, which turns the
     * cross-account aggregate into a single account lookup.
     */
    private List<String> candidateAccounts(
            DetectionContext context,
            Instant from,
            Instant to,
            BigDecimal minAmount,
            BigDecimal maxAmount,
            int minOccurrences) {
        if (context.isStreaming()) {
            Transaction trigger = context.trigger();
            boolean inBand =
                    trigger.getBaseAmount().compareTo(minAmount) >= 0
                            && trigger.getBaseAmount().compareTo(maxAmount) <= 0;
            return inBand ? List.of(accountIdOf(trigger)) : List.of();
        }
        return transactionRepository
                .findStructuringCandidates(from, to, minAmount, maxAmount, minOccurrences)
                .stream()
                .map(AccountActivitySummary::getAccountId)
                .toList();
    }

    private RuleFinding toFinding(
            RuleConfig config,
            DetectionContext context,
            String accountId,
            List<Transaction> evidence,
            BigDecimal minAmount,
            BigDecimal maxAmount,
            int minOccurrences,
            Duration window) {
        Transaction first = evidence.get(0);
        Transaction last = evidence.get(evidence.size() - 1);
        BigDecimal total = sumBaseAmount(evidence);
        String currency = baseCurrencyOf(evidence);
        Duration span = Duration.between(first.getTransactionTimestamp(),
                last.getTransactionTimestamp());

        String explanation =
                ("Account %s recorded %d transactions totalling %s between %s and %s - a span of "
                                + "%d hours %d minutes, inside the %d-hour detection window. Every "
                                + "one of them falls in the %s to %s band, immediately below the %s "
                                + "reporting threshold. Splitting a larger sum into amounts that "
                                + "each stay under the reporting line is the defining signature of "
                                + "structuring; the rule fires at %d or more such transactions.")
                        .formatted(
                                accountId,
                                evidence.size(),
                                Format.money(total, currency),
                                Format.timestamp(first.getTransactionTimestamp()),
                                Format.timestamp(last.getTransactionTimestamp()),
                                span.toHours(),
                                span.toMinutesPart(),
                                window.toHours(),
                                Format.money(minAmount, currency),
                                Format.money(maxAmount, currency),
                                Format.money(maxAmount.add(new BigDecimal("0.01")), currency),
                                minOccurrences);

        RuleFinding.RuleFindingBuilder builder =
                RuleFinding.builder()
                        .ruleCode(RULE_CODE)
                        .ruleName(config.getRuleName())
                        .ruleVersion(config.getConfigVersion())
                        .typology(typology())
                        .severity(config.getSeverity())
                        .ruleWeight(weightOf(config))
                        .customerId(customerIdOf(first))
                        .accountId(accountId)
                        .discriminator(accountId)
                        .title(
                                "%d transactions just below the reporting threshold on %s"
                                        .formatted(evidence.size(), accountId))
                        .explanation(explanation)
                        .detailsJson(
                                Json.of(
                                        "bandMinimum", minAmount,
                                        "bandMaximum", maxAmount,
                                        "thresholdCurrency", currency,
                                        "minOccurrences", minOccurrences,
                                        "observedOccurrences", evidence.size(),
                                        "windowHours", window.toHours(),
                                        "observedSpanMinutes", span.toMinutes(),
                                        "totalBaseAmount", total,
                                        "windowStart", first.getTransactionTimestamp(),
                                        "windowEnd", last.getTransactionTimestamp()))
                        .windowStart(first.getTransactionTimestamp())
                        .windowEnd(last.getTransactionTimestamp())
                        .detectedAt(context.evaluatedAt())
                        .totalAmount(total)
                        .transactionCount(evidence.size());

        for (Transaction transaction : evidence) {
            builder.evidence(RuleFinding.EvidenceItem.of(transaction, "STRUCTURING_LEG"));
        }
        return builder.build();
    }
}
