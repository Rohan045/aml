package com.azentio.aml.detection.rule;

import com.azentio.aml.common.Format;
import com.azentio.aml.common.Json;
import com.azentio.aml.detection.DetectionContext;
import com.azentio.aml.detection.RuleFinding;
import com.azentio.aml.domain.RuleConfig;
import com.azentio.aml.domain.Transaction;
import com.azentio.aml.domain.WatchlistEntry;
import com.azentio.aml.domain.enums.AmlTypology;
import com.azentio.aml.repository.TransactionRepository;
import com.azentio.aml.service.WatchlistService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Sanctioned, adverse-media or internally listed counterparty by name, bank or account number.
 *
 * <p>This complements the jurisdiction rule: a sanctioned entity banking in a perfectly ordinary
 * country would otherwise pass unnoticed. Matching is done against the same normalised form the
 * watchlist was loaded in (upper case, punctuation collapsed), so "Volkov Trading, LLC" and "VOLKOV
 * TRADING LLC" resolve to the same subject.
 *
 * <p>As with jurisdictions, findings are grouped per account and counterparty so ongoing traffic
 * with one listed entity produces a single, evidence-rich alert.
 */
@Component
public class HighRiskCounterpartyRule extends AbstractDetectionRule {

    private static final Logger log = LoggerFactory.getLogger(HighRiskCounterpartyRule.class);
    private static final String RULE_CODE = "HIGH_RISK_COUNTERPARTY";
    private static final int DEFAULT_WINDOW_HOURS = 24;

    private final TransactionRepository transactionRepository;

    public HighRiskCounterpartyRule(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    @Override
    public String ruleCode() {
        return RULE_CODE;
    }

    @Override
    public AmlTypology typology() {
        return AmlTypology.HIGH_RISK_COUNTERPARTY;
    }

    @Override
    public List<RuleFinding> evaluate(RuleConfig config, DetectionContext context) {
        Set<String> listedValues = context.watchlist().blockingCounterpartyValues();
        if (listedValues.isEmpty()) {
            log.debug("{}: no active counterparty watchlist entries; rule is inert", RULE_CODE);
            return List.of();
        }

        List<Transaction> exposed =
                context.isStreaming()
                        ? List.of(context.trigger())
                        : transactionRepository.findHighRiskCounterpartyExposure(
                                queryStart(config, context, DEFAULT_WINDOW_HOURS),
                                context.windowEnd(),
                                listedValues);

        Map<String, Exposure> grouped = new LinkedHashMap<>();
        for (Transaction transaction : exposed) {
            Optional<Hit> hit = firstHit(transaction, context.watchlist());
            if (hit.isEmpty()) {
                continue;
            }
            String accountId = accountIdOf(transaction);
            String key = accountId + "|" + hit.get().normalizedValue();
            grouped.computeIfAbsent(key, ignored -> new Exposure(accountId, hit.get()))
                    .transactions()
                    .add(transaction);
        }

        List<RuleFinding> findings = new ArrayList<>();
        grouped.values().forEach(exposure -> findings.add(toFinding(config, context, exposure)));
        return findings;
    }

    private Optional<Hit> firstHit(Transaction transaction, WatchlistService.Snapshot watchlist) {
        record Field(String name, String value) {}
        List<Field> fields =
                List.of(
                        new Field("counterpartyName", transaction.getCounterpartyName()),
                        new Field("counterpartyBank", transaction.getCounterpartyBank()),
                        new Field("counterpartyAccount", transaction.getCounterpartyAccount()));
        for (Field field : fields) {
            if (field.value() == null || field.value().isBlank()) {
                continue;
            }
            Optional<WatchlistEntry> entry = watchlist.matchCounterparty(field.value());
            if (entry.isPresent()) {
                return Optional.of(
                        new Hit(
                                WatchlistService.normalize(field.value()),
                                field.value(),
                                field.name(),
                                entry.get()));
            }
        }
        return Optional.empty();
    }

    private RuleFinding toFinding(
            RuleConfig config, DetectionContext context, Exposure exposure) {
        WatchlistEntry entry = exposure.hit().entry();
        List<Transaction> transactions = exposure.transactions();
        BigDecimal total = sumBaseAmount(transactions);
        String currency = baseCurrencyOf(transactions);
        Transaction first = transactions.get(0);
        Transaction last = transactions.get(transactions.size() - 1);

        String explanation =
                ("Account %s exchanged %s across %d transaction(s) with \"%s\", matched on the %s "
                                + "field against the %s list (source: %s, list risk weight %d). "
                                + "Dealing with a listed counterparty is reportable in its own "
                                + "right; the amount is recorded for context but does not affect "
                                + "whether the alert is raised.")
                        .formatted(
                                exposure.accountId(),
                                Format.money(total, currency),
                                transactions.size(),
                                exposure.hit().rawValue(),
                                exposure.hit().field(),
                                entry.getListType().name().replace('_', ' '),
                                entry.getSource() == null ? "internal" : entry.getSource(),
                                entry.getRiskWeight());

        RuleFinding.RuleFindingBuilder builder =
                RuleFinding.builder()
                        .ruleCode(RULE_CODE)
                        .ruleName(config.getRuleName())
                        .ruleVersion(config.getConfigVersion())
                        .typology(typology())
                        .severity(config.getSeverity())
                        .ruleWeight(weightOf(config))
                        .contextUplift(upliftFrom(Optional.of(entry)))
                        .customerId(customerIdOf(first))
                        .accountId(exposure.accountId())
                        .discriminator(exposure.hit().normalizedValue())
                        .title("Listed counterparty: %s".formatted(exposure.hit().rawValue()))
                        .explanation(explanation)
                        .detailsJson(
                                Json.of(
                                        "matchedValue", exposure.hit().rawValue(),
                                        "normalizedValue", exposure.hit().normalizedValue(),
                                        "matchedField", exposure.hit().field(),
                                        "listType", entry.getListType().name(),
                                        "listSource", entry.getSource(),
                                        "listRiskWeight", entry.getRiskWeight(),
                                        "exposedTransactions", transactions.size(),
                                        "totalBaseAmount", total))
                        .windowStart(first.getTransactionTimestamp())
                        .windowEnd(last.getTransactionTimestamp())
                        .detectedAt(context.evaluatedAt())
                        .totalAmount(total)
                        .transactionCount(transactions.size());

        for (Transaction transaction : transactions) {
            builder.evidence(
                    RuleFinding.EvidenceItem.of(
                            transaction,
                            "COUNTERPARTY_EXPOSURE",
                            "Matched on " + exposure.hit().field()));
        }
        return builder.build();
    }

    private record Hit(
            String normalizedValue, String rawValue, String field, WatchlistEntry entry) {}

    private record Exposure(String accountId, Hit hit, List<Transaction> transactions) {
        private Exposure(String accountId, Hit hit) {
            this(accountId, hit, new ArrayList<>());
        }
    }
}
