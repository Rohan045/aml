package com.azentio.aml.detection.rule;

import com.azentio.aml.detection.DetectionContext;
import com.azentio.aml.detection.DetectionRule;
import com.azentio.aml.domain.RuleConfig;
import com.azentio.aml.domain.Transaction;
import com.azentio.aml.domain.WatchlistEntry;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Shared plumbing for the rule implementations: window arithmetic, the contextual scoring uplift and
 * the small null-safe accessors every rule needs.
 *
 * <p>Window arithmetic differs by execution mode and getting it wrong is the difference between a
 * rule that fires correctly and one that silently misses patterns, so it lives here rather than
 * being re-derived in each rule.
 */
abstract class AbstractDetectionRule implements DetectionRule {

    /**
     * The interval to query.
     *
     * <p>Streaming anchors the window on the arriving transaction, which is exact. A sweep queries
     * the whole interval it was given and leaves precise rolling-window confirmation to the rule,
     * because the sweep interval is normally wider than any single rule's window.
     */
    protected Instant queryStart(RuleConfig config, DetectionContext context, int defaultHours) {
        if (!context.isStreaming()) {
            return context.windowStart();
        }
        return context.windowEnd().minus(ruleWindow(config, defaultHours));
    }

    protected Duration ruleWindow(RuleConfig config, int defaultHours) {
        Integer hours = config.getTimeWindowHours();
        return Duration.ofHours(hours == null || hours < 1 ? defaultHours : hours);
    }

    protected BigDecimal threshold(RuleConfig config, BigDecimal fallback) {
        return config.getThresholdAmount() == null ? fallback : config.getThresholdAmount();
    }

    protected int minOccurrences(RuleConfig config, int fallback) {
        Integer configured = config.getMinOccurrences();
        return configured == null || configured < 1 ? fallback : configured;
    }

    protected int weightOf(RuleConfig config) {
        return config.getRiskWeight() == null ? 0 : config.getRiskWeight();
    }

    /**
     * Contextual uplift from a watchlist hit, damped to a third of the entry's own weight.
     *
     * <p>The rule weight already reflects how suspicious the pattern is; the list weight only
     * modulates it. Applying the full list weight would let a single sanctions hit saturate the
     * 0-100 score and flatten the ranking the analyst queue depends on.
     */
    protected int upliftFrom(Optional<WatchlistEntry> entry) {
        return entry.map(WatchlistEntry::getRiskWeight).map(weight -> weight / 3).orElse(0);
    }

    protected static String accountIdOf(Transaction transaction) {
        return transaction.getAccount() == null ? null : transaction.getAccount().getAccountId();
    }

    protected static String customerIdOf(Transaction transaction) {
        return transaction.getCustomer() == null ? null : transaction.getCustomer().getCustomerId();
    }

    protected static BigDecimal sumBaseAmount(List<Transaction> transactions) {
        BigDecimal total = BigDecimal.ZERO;
        for (Transaction transaction : transactions) {
            total = total.add(transaction.getBaseAmount());
        }
        return total;
    }

    protected static String baseCurrencyOf(List<Transaction> transactions) {
        return transactions.isEmpty() ? "USD" : transactions.get(0).getBaseCurrency();
    }

    /** First non-blank country code on either leg of a transaction. */
    protected static String anyCountry(Transaction transaction) {
        if (transaction.getCounterpartyCountry() != null
                && !transaction.getCounterpartyCountry().isBlank()) {
            return transaction.getCounterpartyCountry();
        }
        if (transaction.getDestinationCountry() != null
                && !transaction.getDestinationCountry().isBlank()) {
            return transaction.getDestinationCountry();
        }
        return transaction.getOriginCountry();
    }
}
