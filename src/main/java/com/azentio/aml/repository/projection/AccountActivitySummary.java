package com.azentio.aml.repository.projection;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Aggregate of one account's activity inside a detection window.
 *
 * <p>Returned by the grouping queries that identify structuring and round-amount candidates, so the
 * engine can decide which accounts are worth a full evidence fetch without dragging every
 * transaction in the window into memory.
 */
public interface AccountActivitySummary {

    String getAccountId();

    long getTxnCount();

    BigDecimal getTotalAmount();

    Instant getFirstAt();

    Instant getLastAt();
}
