package com.azentio.aml.detection;

import com.azentio.aml.domain.Transaction;
import com.azentio.aml.service.WatchlistService;
import java.time.Instant;
import java.util.Optional;

/**
 * Everything a rule needs to evaluate one detection pass.
 *
 * <p>The same context type serves both execution modes:
 *
 * <ul>
 *   <li><b>Sweep</b> - {@code trigger} is null and the rule scans the whole {@code [windowStart,
 *       windowEnd)} interval across all accounts. Used for bulk loads and the scheduled re-scan.
 *   <li><b>Streaming</b> - {@code trigger} is the transaction that has just arrived. Rules narrow
 *       their queries to that transaction's account or customer, which is what keeps per-message
 *       latency sub-second instead of proportional to book size.
 * </ul>
 *
 * <p>The watchlist {@code snapshot} is taken once per pass and shared by every rule, so a sweep is
 * internally consistent even if the sanctions list is edited while it runs.
 */
public record DetectionContext(
        Instant windowStart,
        Instant windowEnd,
        Instant evaluatedAt,
        WatchlistService.Snapshot watchlist,
        Transaction trigger) {

    /** Streaming pass for a single newly arrived transaction. */
    public static DetectionContext forTransaction(
            Transaction transaction, Instant windowStart, WatchlistService.Snapshot watchlist) {
        // The window is half-open, so it must extend just past the trigger for it to be included.
        Instant windowEnd = transaction.getTransactionTimestamp().plusMillis(1);
        return new DetectionContext(
                windowStart, windowEnd, Instant.now(), watchlist, transaction);
    }

    /** Bulk pass over a time window. */
    public static DetectionContext forWindow(
            Instant windowStart, Instant windowEnd, WatchlistService.Snapshot watchlist) {
        return new DetectionContext(windowStart, windowEnd, Instant.now(), watchlist, null);
    }

    public boolean isStreaming() {
        return trigger != null;
    }

    public Optional<Transaction> triggerTransaction() {
        return Optional.ofNullable(trigger);
    }

    /** The account a streaming pass is confined to, or empty during a sweep. */
    public Optional<String> scopedAccountId() {
        return triggerTransaction().map(t -> t.getAccount().getAccountId());
    }

    /** The customer a streaming pass is confined to, or empty during a sweep. */
    public Optional<String> scopedCustomerId() {
        return triggerTransaction().map(t -> t.getCustomer().getCustomerId());
    }

    /** Re-bases the window start without losing the rest of the context. */
    public DetectionContext withWindowStart(Instant newStart) {
        return new DetectionContext(newStart, windowEnd, evaluatedAt, watchlist, trigger);
    }
}
