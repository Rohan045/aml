package com.azentio.aml.detection;

import static com.azentio.aml.detection.TestFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.azentio.aml.domain.Transaction;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class RollingWindowTest {

    @Test
    void densestWindowReturnsSortedLargestQualifyingHalfOpenWindow() {
        Transaction late = txn("RW-4", "ACC", "CUST", bd("100.00"), BASE_TIME.plusSeconds(25 * 3600));
        Transaction first = txn("RW-1", "ACC", "CUST", bd("100.00"), BASE_TIME);
        Transaction second = txn("RW-2", "ACC", "CUST", bd("100.00"), BASE_TIME.plusSeconds(3600));
        Transaction third = txn("RW-3", "ACC", "CUST", bd("100.00"), BASE_TIME.plusSeconds(2 * 3600));

        assertThat(RollingWindow.densestWindow(List.of(late, third, first, second), Duration.ofHours(24), 3))
                .hasValueSatisfying(window -> assertThat(window)
                        .extracting(Transaction::getExternalTxnId)
                        .containsExactly("RW-1", "RW-2", "RW-3"));
    }

    @Test
    void densestWindowTreatsExactDurationBoundaryAsOutsideHalfOpenWindow() {
        List<Transaction> txns = List.of(
                txn("RW-1", "ACC", "CUST", bd("100.00"), BASE_TIME),
                txn("RW-2", "ACC", "CUST", bd("100.00"), BASE_TIME.plusSeconds(12 * 3600)),
                txn("RW-3", "ACC", "CUST", bd("100.00"), BASE_TIME.plusSeconds(24 * 3600)));

        assertThat(RollingWindow.densestWindow(txns, Duration.ofHours(24), 3)).isEmpty();
    }

    @Test
    void betweenReturnsOnlyTransactionsInHalfOpenIntervalSortedByTime() {
        List<Transaction> result = RollingWindow.between(List.of(
                        txn("BEFORE", "ACC", "CUST", bd("1.00"), BASE_TIME.minusSeconds(1)),
                        txn("END", "ACC", "CUST", bd("1.00"), BASE_TIME.plusSeconds(10)),
                        txn("IN-2", "ACC", "CUST", bd("1.00"), BASE_TIME.plusSeconds(5)),
                        txn("IN-1", "ACC", "CUST", bd("1.00"), BASE_TIME)),
                BASE_TIME,
                BASE_TIME.plusSeconds(10));

        assertThat(result).extracting(Transaction::getExternalTxnId).containsExactly("IN-1", "IN-2");
    }
}
