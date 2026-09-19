package com.azentio.aml.detection.rule;

import com.azentio.aml.common.Format;
import com.azentio.aml.common.Json;
import com.azentio.aml.detection.DetectionContext;
import com.azentio.aml.detection.RuleFinding;
import com.azentio.aml.detection.RuleParameters;
import com.azentio.aml.domain.RuleConfig;
import com.azentio.aml.domain.Transaction;
import com.azentio.aml.domain.enums.AmlTypology;
import com.azentio.aml.repository.TransactionRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Business rule 1 - any single transaction at or above the CTR reporting threshold (USD 10,000
 * equivalent) is flagged for review.
 *
 * <p>This is the one typology that is deliberately alerted per transaction rather than aggregated:
 * each reportable transaction is an individually reportable event, so folding several into one alert
 * would misrepresent the filing obligation. The de-duplication key therefore carries the
 * transaction's own external id, which also makes re-ingesting the same record idempotent.
 */
@Component
public class ThresholdBreachRule extends AbstractDetectionRule {

    private static final Logger log = LoggerFactory.getLogger(ThresholdBreachRule.class);
    private static final String RULE_CODE = "CTR_THRESHOLD_10K";
    private static final BigDecimal DEFAULT_THRESHOLD = new BigDecimal("10000.00");

    private final TransactionRepository transactionRepository;

    public ThresholdBreachRule(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    @Override
    public String ruleCode() {
        return RULE_CODE;
    }

    @Override
    public AmlTypology typology() {
        return AmlTypology.THRESHOLD_BREACH;
    }

    @Override
    public List<RuleFinding> evaluate(RuleConfig config, DetectionContext context) {
        BigDecimal threshold = threshold(config, DEFAULT_THRESHOLD);
        RuleParameters parameters = RuleParameters.of(config);
        Set<String> applicableTypes = Set.copyOf(parameters.strings("appliesTo"));
        boolean inclusive = parameters.flag("inclusive", true);

        List<Transaction> breaches =
                context.isStreaming()
                        ? List.of(context.trigger())
                        : transactionRepository.findThresholdBreaches(
                                context.windowStart(), context.windowEnd(), threshold);

        List<RuleFinding> findings = new ArrayList<>();
        for (Transaction transaction : breaches) {
            if (!breachesThreshold(transaction, threshold, inclusive)) {
                continue;
            }
            if (!applicableTypes.isEmpty()
                    && !applicableTypes.contains(transaction.getTransactionType().name())) {
                continue;
            }
            findings.add(toFinding(config, context, transaction, threshold, inclusive));
        }
        if (!findings.isEmpty()) {
            log.debug("{} raised {} threshold findings", RULE_CODE, findings.size());
        }
        return findings;
    }

    private boolean breachesThreshold(
            Transaction transaction, BigDecimal threshold, boolean inclusive) {
        int comparison = transaction.getBaseAmount().compareTo(threshold);
        return inclusive ? comparison >= 0 : comparison > 0;
    }

    private RuleFinding toFinding(
            RuleConfig config,
            DetectionContext context,
            Transaction transaction,
            BigDecimal threshold,
            boolean inclusive) {
        String currency = transaction.getBaseCurrency();
        String explanation =
                ("Transaction %s of %s on account %s %s the reporting threshold of %s. "
                                + "Original value %s was normalised at a rate of %s. "
                                + "Channel: %s; counterparty: %s. "
                                + "Any single transaction at or above this threshold is reportable "
                                + "and must be reviewed regardless of the customer's profile.")
                        .formatted(
                                transaction.getExternalTxnId(),
                                Format.money(transaction.getBaseAmount(), currency),
                                accountIdOf(transaction),
                                inclusive ? "reaches or exceeds" : "exceeds",
                                Format.money(threshold, currency),
                                Format.money(
                                        transaction.getAmount(), transaction.getCurrency()),
                                Format.number(transaction.getExchangeRate()),
                                transaction.getChannel() == null
                                        ? "not recorded"
                                        : transaction.getChannel().name(),
                                transaction.getCounterpartyName() == null
                                        ? "not recorded"
                                        : transaction.getCounterpartyName());

        return RuleFinding.builder()
                .ruleCode(RULE_CODE)
                .ruleName(config.getRuleName())
                .ruleVersion(config.getConfigVersion())
                .typology(typology())
                .severity(config.getSeverity())
                .ruleWeight(weightOf(config))
                .customerId(customerIdOf(transaction))
                .accountId(accountIdOf(transaction))
                .discriminator(transaction.getExternalTxnId())
                .title(
                        "Reportable transaction of %s"
                                .formatted(Format.money(transaction.getBaseAmount(), currency)))
                .explanation(explanation)
                .detailsJson(
                        Json.of(
                                "thresholdAmount", threshold,
                                "thresholdCurrency", currency,
                                "inclusive", inclusive,
                                "observedBaseAmount", transaction.getBaseAmount(),
                                "originalAmount", transaction.getAmount(),
                                "originalCurrency", transaction.getCurrency(),
                                "exchangeRate", transaction.getExchangeRate(),
                                "transactionType", transaction.getTransactionType().name(),
                                "externalTxnId", transaction.getExternalTxnId()))
                .windowStart(transaction.getTransactionTimestamp())
                .windowEnd(transaction.getTransactionTimestamp())
                .detectedAt(context.evaluatedAt())
                .totalAmount(transaction.getBaseAmount())
                .transactionCount(1)
                .evidence(RuleFinding.EvidenceItem.of(transaction, "THRESHOLD_BREACH"))
                .build();
    }
}
