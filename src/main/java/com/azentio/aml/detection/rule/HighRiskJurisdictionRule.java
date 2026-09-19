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
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Business rule 4 - exposure to a sanctioned or high-risk jurisdiction always alerts, whatever the
 * amount.
 *
 * <p>Because the rule has no amount threshold, an account with regular traffic to one listed country
 * could generate an alert per transaction. Findings are therefore grouped per account and
 * jurisdiction for the window: the analyst gets one alert naming the country, with every exposed
 * transaction attached as evidence. Two different listed countries remain two alerts, because they
 * are two different regulatory exposures.
 */
@Component
public class HighRiskJurisdictionRule extends AbstractDetectionRule {

    private static final Logger log = LoggerFactory.getLogger(HighRiskJurisdictionRule.class);
    private static final String RULE_CODE = "HIGH_RISK_JURISDICTION";
    private static final int DEFAULT_WINDOW_HOURS = 24;

    private final TransactionRepository transactionRepository;

    public HighRiskJurisdictionRule(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    @Override
    public String ruleCode() {
        return RULE_CODE;
    }

    @Override
    public AmlTypology typology() {
        return AmlTypology.HIGH_RISK_JURISDICTION;
    }

    @Override
    public List<RuleFinding> evaluate(RuleConfig config, DetectionContext context) {
        Set<String> listedCountries = context.watchlist().blockingCountryCodes();
        if (listedCountries.isEmpty()) {
            log.debug("{}: no active jurisdiction watchlist entries; rule is inert", RULE_CODE);
            return List.of();
        }

        List<Transaction> exposed =
                context.isStreaming()
                        ? List.of(context.trigger())
                        : transactionRepository.findHighRiskJurisdictionExposure(
                                queryStart(config, context, DEFAULT_WINDOW_HOURS),
                                context.windowEnd(),
                                listedCountries);

        // Key: account + listed country, so each distinct regulatory exposure gets its own alert.
        Map<String, Exposure> grouped = new LinkedHashMap<>();
        for (Transaction transaction : exposed) {
            Optional<Hit> hit = firstHit(transaction, context.watchlist());
            if (hit.isEmpty()) {
                continue;
            }
            String accountId = accountIdOf(transaction);
            String key = accountId + "|" + hit.get().countryCode();
            grouped.computeIfAbsent(key, ignored -> new Exposure(accountId, hit.get()))
                    .add(transaction, hit.get().leg());
        }

        List<RuleFinding> findings = new ArrayList<>();
        grouped.values().forEach(exposure -> findings.add(toFinding(config, context, exposure)));
        return findings;
    }

    /** The first leg of the transaction that touches a listed jurisdiction. */
    private Optional<Hit> firstHit(Transaction transaction, WatchlistService.Snapshot watchlist) {
        record Leg(String name, String value) {}
        List<Leg> legs =
                List.of(
                        new Leg("counterparty", transaction.getCounterpartyCountry()),
                        new Leg("destination", transaction.getDestinationCountry()),
                        new Leg("origin", transaction.getOriginCountry()));
        for (Leg leg : legs) {
            if (leg.value() == null || leg.value().isBlank()) {
                continue;
            }
            Optional<WatchlistEntry> entry = watchlist.matchCountry(leg.value());
            if (entry.isPresent()) {
                return Optional.of(
                        new Hit(leg.value().toUpperCase(Locale.ROOT), leg.name(), entry.get()));
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
                ("Account %s transacted %s across %d transaction(s) involving %s (%s), which "
                                + "appears on the %s list maintained by %s. The exposure was "
                                + "detected on the %s leg of the transaction. Jurisdiction "
                                + "exposure is alerted regardless of value, because the regulatory "
                                + "obligation attaches to the counterparty's location rather than "
                                + "to the size of the payment.")
                        .formatted(
                                exposure.accountId(),
                                Format.money(total, currency),
                                transactions.size(),
                                entry.getDisplayName() == null
                                        ? exposure.hit().countryCode()
                                        : entry.getDisplayName(),
                                exposure.hit().countryCode(),
                                entry.getListType().name().replace('_', ' '),
                                entry.getSource() == null ? "an internal source" : entry.getSource(),
                                exposure.hit().leg());

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
                        .discriminator(exposure.hit().countryCode())
                        .title(
                                "Exposure to high-risk jurisdiction %s"
                                        .formatted(exposure.hit().countryCode()))
                        .explanation(explanation)
                        .detailsJson(
                                Json.of(
                                        "countryCode", exposure.hit().countryCode(),
                                        "countryName", entry.getDisplayName(),
                                        "listType", entry.getListType().name(),
                                        "listSource", entry.getSource(),
                                        "listRiskWeight", entry.getRiskWeight(),
                                        "matchedLeg", exposure.hit().leg(),
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
                            "JURISDICTION_EXPOSURE",
                            "Matched on the " + exposure.hit().leg() + " country"));
        }
        return builder.build();
    }

    private record Hit(String countryCode, String leg, WatchlistEntry entry) {}

    /** Mutable accumulator for one account/jurisdiction pair during grouping. */
    private static final class Exposure {
        private final String accountId;
        private final Hit hit;
        private final List<Transaction> transactions = new ArrayList<>();

        private Exposure(String accountId, Hit hit) {
            this.accountId = accountId;
            this.hit = hit;
        }

        private void add(Transaction transaction, String leg) {
            transactions.add(transaction);
        }

        private String accountId() {
            return accountId;
        }

        private Hit hit() {
            return hit;
        }

        private List<Transaction> transactions() {
            return transactions;
        }
    }
}
