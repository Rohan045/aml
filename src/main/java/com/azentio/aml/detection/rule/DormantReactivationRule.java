package com.azentio.aml.detection.rule;

import com.azentio.aml.common.Format;
import com.azentio.aml.common.Json;
import com.azentio.aml.detection.DetectionContext;
import com.azentio.aml.detection.RuleFinding;
import com.azentio.aml.detection.RuleParameters;
import com.azentio.aml.domain.RuleConfig;
import com.azentio.aml.domain.Transaction;
import com.azentio.aml.domain.enums.AccountStatus;
import com.azentio.aml.domain.enums.AmlTypology;
import com.azentio.aml.repository.TransactionRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Significant activity on an account the bank had already classified as dormant or inactive.
 *
 * <p>A long-idle account that suddenly moves material value is a classic mule signature: the
 * account was opened legitimately, went quiet, and has been sold or taken over. Dormancy is read
 * from the bank's own account status rather than recomputed from transaction history, so the rule
 * needs no historical scan and reflects the classification the bank itself is operating on.
 */
@Component
public class DormantReactivationRule extends AbstractDetectionRule {

    private static final String RULE_CODE = "DORMANT_REACTIVATION";
    private static final BigDecimal DEFAULT_MIN_AMOUNT = new BigDecimal("5000.00");
    private static final int DEFAULT_WINDOW_HOURS = 24;
    private static final Set<AccountStatus> DEFAULT_STATUSES =
            EnumSet.of(AccountStatus.DORMANT, AccountStatus.INACTIVE);

    private final TransactionRepository transactionRepository;

    public DormantReactivationRule(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    @Override
    public String ruleCode() {
        return RULE_CODE;
    }

    @Override
    public AmlTypology typology() {
        return AmlTypology.DORMANT_ACCOUNT_REACTIVATION;
    }

    @Override
    public List<RuleFinding> evaluate(RuleConfig config, DetectionContext context) {
        BigDecimal minAmount = threshold(config, DEFAULT_MIN_AMOUNT);
        RuleParameters parameters = RuleParameters.of(config);
        Set<AccountStatus> statuses = applicableStatuses(parameters);
        int dormancyDays =
                parameters.integer(
                        "dormancyDays",
                        config.getLookbackDays() == null ? 180 : config.getLookbackDays());

        List<Transaction> reactivations =
                context.isStreaming()
                        ? streamingCandidates(context, minAmount, statuses)
                        : transactionRepository.findDormantAccountActivity(
                                queryStart(config, context, DEFAULT_WINDOW_HOURS),
                                context.windowEnd(),
                                minAmount,
                                statuses);

        // One alert per account: several movements on a reawakened account are one event.
        Map<String, List<Transaction>> byAccount = new LinkedHashMap<>();
        for (Transaction transaction : reactivations) {
            byAccount
                    .computeIfAbsent(accountIdOf(transaction), ignored -> new ArrayList<>())
                    .add(transaction);
        }

        List<RuleFinding> findings = new ArrayList<>();
        byAccount.forEach(
                (accountId, transactions) ->
                        findings.add(
                                toFinding(
                                        config,
                                        context,
                                        accountId,
                                        transactions,
                                        minAmount,
                                        dormancyDays)));
        return findings;
    }

    private List<Transaction> streamingCandidates(
            DetectionContext context, BigDecimal minAmount, Set<AccountStatus> statuses) {
        Transaction trigger = context.trigger();
        boolean qualifies =
                trigger.getBaseAmount().compareTo(minAmount) >= 0
                        && trigger.getAccount() != null
                        && statuses.contains(trigger.getAccount().getAccountStatus());
        return qualifies ? List.of(trigger) : List.of();
    }

    private Set<AccountStatus> applicableStatuses(RuleParameters parameters) {
        List<String> configured = parameters.strings("applicableStatuses");
        if (configured.isEmpty()) {
            return DEFAULT_STATUSES;
        }
        Set<AccountStatus> statuses = EnumSet.noneOf(AccountStatus.class);
        for (String name : configured) {
            AccountStatus status =
                    com.azentio.aml.common.EnumSupport.parse(AccountStatus.class, name, null);
            if (status != null) {
                statuses.add(status);
            }
        }
        return statuses.isEmpty() ? DEFAULT_STATUSES : statuses;
    }

    private RuleFinding toFinding(
            RuleConfig config,
            DetectionContext context,
            String accountId,
            List<Transaction> evidence,
            BigDecimal minAmount,
            int dormancyDays) {
        Transaction first = evidence.get(0);
        Transaction last = evidence.get(evidence.size() - 1);
        BigDecimal total = sumBaseAmount(evidence);
        String currency = baseCurrencyOf(evidence);
        String status =
                first.getAccount() == null || first.getAccount().getAccountStatus() == null
                        ? "dormant"
                        : first.getAccount().getAccountStatus().name();

        String explanation =
                ("Account %s is classified as %s yet recorded %d transaction(s) totalling %s "
                                + "between %s and %s, each at or above the %s materiality floor. "
                                + "Accounts idle for %d days or more that suddenly move "
                                + "significant value are a recognised money-mule pattern, because "
                                + "the account's history gives the activity an appearance of "
                                + "legitimacy it has not earned.")
                        .formatted(
                                accountId,
                                status,
                                evidence.size(),
                                Format.money(total, currency),
                                Format.timestamp(first.getTransactionTimestamp()),
                                Format.timestamp(last.getTransactionTimestamp()),
                                Format.money(minAmount, currency),
                                dormancyDays);

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
                        .title("Dormant account %s reactivated with %s".formatted(
                                accountId, Format.money(total, currency)))
                        .explanation(explanation)
                        .detailsJson(
                                Json.of(
                                        "accountStatus", status,
                                        "dormancyDays", dormancyDays,
                                        "minimumAmount", minAmount,
                                        "observedTransactions", evidence.size(),
                                        "totalBaseAmount", total))
                        .windowStart(first.getTransactionTimestamp())
                        .windowEnd(last.getTransactionTimestamp())
                        .detectedAt(context.evaluatedAt())
                        .totalAmount(total)
                        .transactionCount(evidence.size());

        for (Transaction transaction : evidence) {
            builder.evidence(RuleFinding.EvidenceItem.of(transaction, "DORMANT_ACTIVITY"));
        }
        return builder.build();
    }
}
