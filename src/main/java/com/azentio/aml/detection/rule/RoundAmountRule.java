package com.azentio.aml.detection.rule;

import com.azentio.aml.common.Format;
import com.azentio.aml.common.Json;
import com.azentio.aml.detection.DetectionContext;
import com.azentio.aml.detection.RollingWindow;
import com.azentio.aml.detection.RuleFinding;
import com.azentio.aml.detection.RuleParameters;
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
import org.springframework.stereotype.Component;

/**
 * Repeated suspiciously round amounts - transactions that are exact multiples of 1,000.
 *
 * <p>Organic payments carry the untidy residue of real prices, taxes and fees; a run of exact
 * round numbers usually means the figure was chosen rather than incurred. On its own this is weak
 * evidence, which is why the rule carries a low weight and only fires on repetition. Its value is
 * corroborative: combined with structuring or rapid movement on the same customer it lifts the
 * composite score into a band an analyst will prioritise.
 *
 * <p>Like structuring, database grouping selects candidates over the sweep interval and the exact
 * rolling window is confirmed in memory.
 */
@Component
public class RoundAmountRule extends AbstractDetectionRule {

    private static final String RULE_CODE = "ROUND_AMOUNT_PATTERN";
    private static final BigDecimal DEFAULT_MULTIPLE = new BigDecimal("1000");
    private static final BigDecimal DEFAULT_MIN_AMOUNT = new BigDecimal("1000.00");
    private static final int DEFAULT_WINDOW_HOURS = 24;
    private static final int DEFAULT_MIN_OCCURRENCES = 3;

    private final TransactionRepository transactionRepository;

    public RoundAmountRule(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    @Override
    public String ruleCode() {
        return RULE_CODE;
    }

    @Override
    public AmlTypology typology() {
        return AmlTypology.ROUND_AMOUNT_PATTERN;
    }

    @Override
    public List<RuleFinding> evaluate(RuleConfig config, DetectionContext context) {
        RuleParameters parameters = RuleParameters.of(config);
        BigDecimal multiple = parameters.decimal("roundingMultiple", DEFAULT_MULTIPLE);
        BigDecimal minAmount = threshold(config, DEFAULT_MIN_AMOUNT);
        int minOccurrences = minOccurrences(config, DEFAULT_MIN_OCCURRENCES);
        Duration window = ruleWindow(config, DEFAULT_WINDOW_HOURS);
        Instant from = queryStart(config, context, DEFAULT_WINDOW_HOURS);
        Instant to = context.windowEnd();

        if (multiple.signum() <= 0) {
            return List.of();
        }

        List<String> accounts =
                context.isStreaming()
                        ? streamingCandidate(context, multiple, minAmount)
                        : transactionRepository
                                .findRoundAmountCandidates(
                                        from, to, minAmount, multiple, minOccurrences)
                                .stream()
                                .map(AccountActivitySummary::getAccountId)
                                .toList();

        List<RuleFinding> findings = new ArrayList<>();
        for (String accountId : accounts) {
            List<Transaction> rounded =
                    transactionRepository.findAccountWindow(accountId, from, to).stream()
                            .filter(t -> isRound(t, multiple, minAmount))
                            .toList();
            Optional<List<Transaction>> confirmed =
                    RollingWindow.densestWindow(rounded, window, minOccurrences);
            confirmed.ifPresent(
                    evidence ->
                            findings.add(
                                    toFinding(
                                            config,
                                            context,
                                            accountId,
                                            evidence,
                                            multiple,
                                            minOccurrences,
                                            window)));
        }
        return findings;
    }

    private List<String> streamingCandidate(
            DetectionContext context, BigDecimal multiple, BigDecimal minAmount) {
        Transaction trigger = context.trigger();
        return isRound(trigger, multiple, minAmount)
                ? List.of(accountIdOf(trigger))
                : List.of();
    }

    private boolean isRound(Transaction transaction, BigDecimal multiple, BigDecimal minAmount) {
        return transaction.getBaseAmount().compareTo(minAmount) >= 0
                && transaction.getBaseAmount().remainder(multiple).compareTo(BigDecimal.ZERO) == 0;
    }

    private RuleFinding toFinding(
            RuleConfig config,
            DetectionContext context,
            String accountId,
            List<Transaction> evidence,
            BigDecimal multiple,
            int minOccurrences,
            Duration window) {
        Transaction first = evidence.get(0);
        Transaction last = evidence.get(evidence.size() - 1);
        BigDecimal total = sumBaseAmount(evidence);
        String currency = baseCurrencyOf(evidence);

        String explanation =
                ("Account %s made %d transactions totalling %s between %s and %s, every one an "
                                + "exact multiple of %s. Genuine commercial payments rarely land on "
                                + "round figures repeatedly; a run of %d or more within %d hours "
                                + "suggests amounts chosen by the payer rather than determined by "
                                + "an underlying transaction. This is a supporting indicator and "
                                + "carries a deliberately low weight on its own.")
                        .formatted(
                                accountId,
                                evidence.size(),
                                Format.money(total, currency),
                                Format.timestamp(first.getTransactionTimestamp()),
                                Format.timestamp(last.getTransactionTimestamp()),
                                Format.money(multiple, currency),
                                minOccurrences,
                                window.toHours());

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
                                "%d round-number transactions on %s"
                                        .formatted(evidence.size(), accountId))
                        .explanation(explanation)
                        .detailsJson(
                                Json.of(
                                        "roundingMultiple", multiple,
                                        "minOccurrences", minOccurrences,
                                        "observedOccurrences", evidence.size(),
                                        "windowHours", window.toHours(),
                                        "totalBaseAmount", total))
                        .windowStart(first.getTransactionTimestamp())
                        .windowEnd(last.getTransactionTimestamp())
                        .detectedAt(context.evaluatedAt())
                        .totalAmount(total)
                        .transactionCount(evidence.size());

        for (Transaction transaction : evidence) {
            builder.evidence(RuleFinding.EvidenceItem.of(transaction, "ROUND_AMOUNT"));
        }
        return builder.build();
    }
}
