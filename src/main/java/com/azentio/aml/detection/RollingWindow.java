package com.azentio.aml.detection;

import com.azentio.aml.domain.Transaction;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Exact rolling-window matching over a single account's transactions.
 *
 * <p>The database narrows a sweep to the handful of accounts that could possibly qualify, but its
 * {@code group by} covers the whole sweep interval, which is wider than the rule's window. Three
 * transactions spread over 48 hours are not a 24-hour structuring pattern, so the candidate set has
 * to be confirmed against the real rolling window before an alert is raised - otherwise the rule
 * over-fires on long sweeps and analysts lose trust in it.
 *
 * <p>Confirmation runs here, in memory, on the few rows belonging to one candidate account: a
 * linear two-pointer pass rather than a second database round trip per candidate. Windows are
 * half-open {@code [start, start + duration)}, matching the repository queries.
 */
public final class RollingWindow {

    private RollingWindow() {}

    /**
     * Finds the densest window of {@code duration} containing at least {@code minOccurrences}
     * transactions.
     *
     * @param transactions candidate transactions; need not be sorted
     * @return the transactions inside the best qualifying window, or empty if the pattern does not
     *     actually hold within the window length
     */
    public static Optional<List<Transaction>> densestWindow(
            List<Transaction> transactions, Duration duration, int minOccurrences) {
        if (transactions == null || transactions.size() < minOccurrences || minOccurrences < 1) {
            return Optional.empty();
        }
        List<Transaction> sorted = new ArrayList<>(transactions);
        sorted.sort(Comparator.comparing(Transaction::getTransactionTimestamp));

        int bestStart = -1;
        int bestEnd = -1;
        int left = 0;
        for (int right = 0; right < sorted.size(); right++) {
            Instant rightAt = sorted.get(right).getTransactionTimestamp();
            // Shrink from the left until every member sits inside one window length.
            while (left <= right
                    && Duration.between(sorted.get(left).getTransactionTimestamp(), rightAt)
                                    .compareTo(duration)
                            >= 0) {
                left++;
            }
            int count = right - left + 1;
            if (count >= minOccurrences && count > (bestEnd - bestStart + 1)) {
                bestStart = left;
                bestEnd = right;
            }
        }
        if (bestStart < 0) {
            return Optional.empty();
        }
        return Optional.of(List.copyOf(sorted.subList(bestStart, bestEnd + 1)));
    }

    /** Transactions falling in {@code [from, to)}, sorted by time. */
    public static List<Transaction> between(
            List<Transaction> transactions, Instant from, Instant to) {
        List<Transaction> selected = new ArrayList<>();
        for (Transaction transaction : transactions) {
            Instant at = transaction.getTransactionTimestamp();
            if (!at.isBefore(from) && at.isBefore(to)) {
                selected.add(transaction);
            }
        }
        selected.sort(Comparator.comparing(Transaction::getTransactionTimestamp));
        return selected;
    }
}
