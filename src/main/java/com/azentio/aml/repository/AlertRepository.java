package com.azentio.aml.repository;

import com.azentio.aml.domain.Alert;
import com.azentio.aml.domain.enums.AlertStatus;
import com.azentio.aml.domain.enums.AmlTypology;
import com.azentio.aml.repository.projection.AlertCountByGroup;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Alert access for the detection engine, the analyst queue and the dashboard.
 *
 * <p>The de-duplication contract lives here. Detection threads do not check-then-insert: they
 * attempt {@link #recordRepeatDetection}, and only insert a new alert when that updates no rows.
 * Combined with the unique constraint on {@code dedupe_key}, a losing thread in a genuine race gets
 * a constraint violation rather than a duplicate alert.
 */
public interface AlertRepository extends JpaRepository<Alert, Long> {

    Optional<Alert> findByAlertReference(String alertReference);

    Optional<Alert> findByDedupeKey(String dedupeKey);

    boolean existsByDedupeKey(String dedupeKey);

    /**
     * Folds a repeat detection into the existing alert instead of raising a new one.
     *
     * @return 1 if an existing alert absorbed the detection, 0 if the caller must insert a new one
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            """
            update Alert a
               set a.occurrenceCount = a.occurrenceCount + 1,
                   a.lastDetectedAt  = :detectedAt
             where a.dedupeKey = :dedupeKey
            """)
    int recordRepeatDetection(
            @Param("dedupeKey") String dedupeKey, @Param("detectedAt") Instant detectedAt);

    // ---------------------------------------------------------------------
    // Analyst queue
    // ---------------------------------------------------------------------

    /**
     * Work queue: highest risk first, then oldest, so nothing starves at equal score.
     *
     * <p>The customer and case are fetched in the same query. The queue projection masks the
     * customer's name and shows the case number, and neither is reachable from a lazy proxy -
     * with {@code open-in-view=false} the session is already closed by the time the controller
     * maps the page, so a lazy load here is not a slow query, it is a 500.
     */
    @EntityGraph(attributePaths = {"customer", "amlCase"})
    @Query(
            """
            select a
              from Alert a
             where a.status in :statuses
             order by a.riskScore desc, a.firstDetectedAt asc
            """)
    Page<Alert> findQueue(
            @Param("statuses") Collection<AlertStatus> statuses, Pageable pageable);

    @EntityGraph(attributePaths = {"customer", "amlCase"})
    Page<Alert> findByAssignedToAndStatusIn(
            String assignedTo, Collection<AlertStatus> statuses, Pageable pageable);

    @EntityGraph(attributePaths = {"customer", "amlCase"})
    Page<Alert> findByCustomer_CustomerIdOrderByFirstDetectedAtDesc(
            String customerId, Pageable pageable);

    @EntityGraph(attributePaths = {"customer", "amlCase"})
    Page<Alert> findByTypologyOrderByRiskScoreDesc(AmlTypology typology, Pageable pageable);

    /** Loads an alert with its full explanation payload in one query, for the detail view. */
    @EntityGraph(attributePaths = {"triggeredRules", "evidence", "customer", "amlCase"})
    Optional<Alert> findWithDetailById(Long id);

    /**
     * Alert lookup by id with the associations the queue projection reads.
     *
     * <p>The workflow endpoints return the updated alert straight to the caller, so the entity
     * outlives the service transaction that produced it. Loading the customer and case up front
     * is what keeps that response serialisable.
     */
    @EntityGraph(attributePaths = {"customer", "amlCase"})
    Optional<Alert> findWithCustomerById(Long id);

    // ---------------------------------------------------------------------
    // Case linkage
    // ---------------------------------------------------------------------

    @EntityGraph(attributePaths = {"customer", "amlCase"})
    List<Alert> findByAmlCase_Id(Long caseId);

    @Query(
            """
            select a
              from Alert a
             where a.customer.customerId = :customerId
               and a.amlCase is null
               and a.status <> com.azentio.aml.domain.enums.AlertStatus.CLOSED
             order by a.riskScore desc
            """)
    List<Alert> findUnlinkedOpenAlerts(@Param("customerId") String customerId);

    // ---------------------------------------------------------------------
    // Dashboard aggregates
    // ---------------------------------------------------------------------

    @Query(
            """
            select cast(a.typology as string) as groupKey, count(a) as total
              from Alert a
             where a.firstDetectedAt >= :since
             group by a.typology
             order by count(a) desc
            """)
    List<AlertCountByGroup> countByTypologySince(@Param("since") Instant since);

    @Query(
            """
            select cast(a.severity as string) as groupKey, count(a) as total
              from Alert a
             where a.firstDetectedAt >= :since
             group by a.severity
            """)
    List<AlertCountByGroup> countBySeveritySince(@Param("since") Instant since);

    @Query(
            """
            select cast(a.status as string) as groupKey, count(a) as total
              from Alert a
             group by a.status
            """)
    List<AlertCountByGroup> countByStatus();

    long countByStatusIn(Collection<AlertStatus> statuses);

    long countByFirstDetectedAtGreaterThanEqual(Instant since);
}
