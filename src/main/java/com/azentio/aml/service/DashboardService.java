package com.azentio.aml.service;

import com.azentio.aml.domain.AmlCase;
import com.azentio.aml.domain.enums.AlertStatus;
import com.azentio.aml.domain.enums.CaseStatus;
import com.azentio.aml.repository.AlertRepository;
import com.azentio.aml.repository.AmlCaseRepository;
import com.azentio.aml.repository.CustomerRepository;
import com.azentio.aml.repository.TransactionRepository;
import com.azentio.aml.repository.projection.AlertCountByGroup;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only aggregates for the compliance dashboard.
 *
 * <p>The problem statement asks for alert volumes by typology and severity and for operational
 * metrics such as time to disposition. Everything here is computed from the same tables the
 * investigation workflow writes to - there is no separate reporting store that could disagree with
 * the case file.
 *
 * <p>All queries are bounded by an explicit lookback window so the dashboard cost stays flat as the
 * transaction history grows.
 */
@Service
public class DashboardService {

    /** Default reporting window when the caller does not supply one. */
    private static final int DEFAULT_LOOKBACK_DAYS = 30;

    private final AlertRepository alertRepository;
    private final AmlCaseRepository caseRepository;
    private final TransactionRepository transactionRepository;
    private final CustomerRepository customerRepository;

    public DashboardService(
            AlertRepository alertRepository,
            AmlCaseRepository caseRepository,
            TransactionRepository transactionRepository,
            CustomerRepository customerRepository) {
        this.alertRepository = alertRepository;
        this.caseRepository = caseRepository;
        this.transactionRepository = transactionRepository;
        this.customerRepository = customerRepository;
    }

    /**
     * Snapshot of detection and investigation activity over the requested window.
     *
     * @param lookbackDays reporting window in days; non-positive values fall back to the default
     */
    @Transactional(readOnly = true)
    public DashboardSnapshot snapshot(Integer lookbackDays) {
        int days = lookbackDays == null || lookbackDays <= 0 ? DEFAULT_LOOKBACK_DAYS : lookbackDays;
        Instant since = Instant.now().minus(days, ChronoUnit.DAYS);

        Map<String, Long> byTypology = toMap(alertRepository.countByTypologySince(since));
        Map<String, Long> bySeverity = toMap(alertRepository.countBySeveritySince(since));
        Map<String, Long> byStatus = toMap(alertRepository.countByStatus());

        long alertsInWindow = alertRepository.countByFirstDetectedAtGreaterThanEqual(since);
        long openAlerts = alertRepository.countByStatusIn(AlertService.openStatuses());
        long openCases = caseRepository.countByStatusIn(CaseService.openStatuses());
        long sarsFiled = caseRepository.countBySarFiledTrue();

        Disposition disposition = dispositionMetrics(since);

        return new DashboardSnapshot(
                since,
                Instant.now(),
                days,
                alertsInWindow,
                openAlerts,
                openCases,
                sarsFiled,
                transactionRepository.count(),
                customerRepository.count(),
                byTypology,
                bySeverity,
                byStatus,
                disposition.closedCases(),
                disposition.averageHoursToDisposition(),
                disposition.breachedSla());
    }

    /**
     * Average time from case opening to closure, plus an SLA breach count.
     *
     * <p>Computed in memory over closed cases in the window rather than in SQL: the volume is small
     * (closures, not transactions) and keeping the arithmetic in Java avoids a database-specific
     * date-difference function, which matters because the tests run on H2 and production on
     * PostgreSQL.
     */
    private Disposition dispositionMetrics(Instant since) {
        List<AmlCase> closed =
                caseRepository.findByStatusIn(List.of(CaseStatus.CLOSED), org.springframework.data
                                .domain.Pageable
                                .unpaged())
                        .getContent();

        long total = 0;
        long count = 0;
        long breached = 0;
        for (AmlCase amlCase : closed) {
            if (amlCase.getClosedAt() == null || amlCase.getClosedAt().isBefore(since)) {
                continue;
            }
            count++;
            total += Duration.between(amlCase.getOpenedAt(), amlCase.getClosedAt()).toHours();
            if (amlCase.getDueAt() != null && amlCase.getClosedAt().isAfter(amlCase.getDueAt())) {
                breached++;
            }
        }
        double average = count == 0 ? 0d : Math.round((double) total / count * 10d) / 10d;
        return new Disposition(count, average, breached);
    }

    private Map<String, Long> toMap(List<AlertCountByGroup> counts) {
        Map<String, Long> result = new LinkedHashMap<>();
        for (AlertCountByGroup count : counts) {
            result.put(count.getGroupKey(), count.getTotal());
        }
        return result;
    }

    private record Disposition(long closedCases, double averageHoursToDisposition, long breachedSla) {}

    /**
     * Immutable dashboard payload.
     *
     * @param alertsByTypology alert volume per money-laundering typology
     * @param alertsBySeverity alert volume per severity band
     * @param alertsByStatus current queue composition across all time, for workload sizing
     */
    public record DashboardSnapshot(
            Instant windowStart,
            Instant generatedAt,
            int lookbackDays,
            long alertsInWindow,
            long openAlerts,
            long openCases,
            long sarsFiled,
            long totalTransactions,
            long totalCustomers,
            Map<String, Long> alertsByTypology,
            Map<String, Long> alertsBySeverity,
            Map<String, Long> alertsByStatus,
            long casesClosedInWindow,
            double averageHoursToDisposition,
            long casesBreachingSla) {

        /** Share of alerts still awaiting a decision - the headline backlog indicator. */
        public double openAlertRatio() {
            return alertsInWindow == 0 ? 0d : (double) openAlerts / alertsInWindow;
        }
    }

    /** Exposed for controllers that need the canonical open-alert status list. */
    public static java.util.Collection<AlertStatus> openAlertStatuses() {
        return AlertService.openStatuses();
    }
}
