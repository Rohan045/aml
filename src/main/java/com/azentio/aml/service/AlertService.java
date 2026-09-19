package com.azentio.aml.service;

import com.azentio.aml.common.exception.BusinessRuleException;
import com.azentio.aml.common.exception.NotFoundException;
import com.azentio.aml.detection.RuleFinding;
import com.azentio.aml.domain.Alert;
import com.azentio.aml.domain.enums.AlertDisposition;
import com.azentio.aml.domain.enums.AlertStatus;
import com.azentio.aml.domain.enums.AmlTypology;
import com.azentio.aml.domain.enums.AuditAction;
import com.azentio.aml.repository.AlertRepository;
import com.azentio.aml.security.CurrentUser;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Alert lifecycle: raising and de-duplicating what the engine detects, then the analyst workflow of
 * assignment, triage and disposition.
 *
 * <h2>De-duplication</h2>
 *
 * Findings are grouped by customer, typology, pattern discriminator and time bucket into a
 * deterministic {@code dedupeKey}, so a customer structuring all week yields one alert with a
 * rising occurrence count rather than fifty - redundant alerts are what makes an analyst queue
 * unusable.
 *
 * <p>Concurrency is handled without locking. The "fold into the existing alert" update is attempted
 * first and an insert happens only when it updates no rows; if two detection threads still race,
 * the unique constraint on {@code dedupe_key} rejects the loser, which then retries down the fold
 * path. No alert is lost and none is duplicated.
 *
 * <h2>Immutability</h2>
 *
 * Alerts are never deleted (business rule 6). Closure records the disposition, a mandatory reason
 * and the analyst's identity, and every transition is written to the audit trail.
 */
@Service
public class AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);
    private static final String ENTITY = "Alert";
    private static final Set<AlertStatus> OPEN_STATUSES =
            Set.of(
                    AlertStatus.NEW,
                    AlertStatus.ASSIGNED,
                    AlertStatus.IN_REVIEW,
                    AlertStatus.PENDING_INFO,
                    AlertStatus.ESCALATED);

    private final AlertRepository alertRepository;
    private final AlertWriter alertWriter;
    private final AuditService auditService;

    public AlertService(
            AlertRepository alertRepository, AlertWriter alertWriter, AuditService auditService) {
        this.alertRepository = alertRepository;
        this.alertWriter = alertWriter;
        this.auditService = auditService;
    }

    // ------------------------------------------------------------------
    // Detection path
    // ------------------------------------------------------------------

    /**
     * Converts rule findings into alerts, aggregating and de-duplicating as it goes.
     *
     * @param dedupeWindowHours bucket width; detections of the same pattern inside one bucket fold
     *     together, while a recurrence next week correctly raises a fresh alert
     */
    public Outcome raise(List<RuleFinding> findings, int dedupeWindowHours) {
        if (findings == null || findings.isEmpty()) {
            return Outcome.EMPTY;
        }
        Map<String, List<RuleFinding>> grouped = new LinkedHashMap<>();
        for (RuleFinding finding : findings) {
            grouped.computeIfAbsent(
                            dedupeKey(finding, dedupeWindowHours), key -> new ArrayList<>())
                    .add(finding);
        }

        int created = 0;
        int aggregated = 0;
        for (Map.Entry<String, List<RuleFinding>> entry : grouped.entrySet()) {
            if (raiseOne(entry.getKey(), entry.getValue())) {
                created++;
            } else {
                aggregated++;
            }
        }
        if (created > 0 || aggregated > 0) {
            log.info(
                    "Detection raised {} new alerts and folded {} into existing alerts",
                    created,
                    aggregated);
        }
        return new Outcome(created, aggregated);
    }

    /**
     * @return true if a new alert was inserted, false if an existing alert absorbed the detection
     */
    private boolean raiseOne(String dedupeKey, List<RuleFinding> findings) {
        Instant detectedAt = findings.get(0).getDetectedAt();
        if (alertWriter.foldIntoExisting(dedupeKey, findings, detectedAt)) {
            return false;
        }
        try {
            alertWriter.insert(dedupeKey, findings);
            return true;
        } catch (DataIntegrityViolationException ex) {
            // Another detection thread inserted the same pattern between the check and the insert.
            log.debug("Lost the insert race on dedupe key {}; folding instead", dedupeKey);
            alertWriter.foldIntoExisting(dedupeKey, findings, detectedAt);
            return false;
        }
    }

    /**
     * Deterministic de-duplication key.
     *
     * <p>Bucketing by detection window rather than simply asking "does an open alert already exist"
     * is what allows a genuinely new occurrence of the same pattern to raise its own alert instead
     * of quietly incrementing a months-old one.
     */
    String dedupeKey(RuleFinding finding, int dedupeWindowHours) {
        int windowHours = dedupeWindowHours < 1 ? 24 : dedupeWindowHours;
        long bucket =
                finding.getWindowStart().atZone(ZoneOffset.UTC).toEpochSecond()
                        / (windowHours * 3600L);
        return String.join(
                "|",
                finding.getCustomerId(),
                finding.getTypology().name(),
                finding.getDiscriminator() == null ? "-" : finding.getDiscriminator(),
                Long.toString(bucket));
    }

    // ------------------------------------------------------------------
    // Analyst workflow
    // ------------------------------------------------------------------

    /** Work queue, highest risk first so the most serious alerts sort to the top. */
    @Transactional(readOnly = true)
    public Page<Alert> queue(Collection<AlertStatus> statuses, Pageable pageable) {
        Collection<AlertStatus> effective =
                statuses == null || statuses.isEmpty() ? OPEN_STATUSES : statuses;
        return alertRepository.findQueue(effective, pageable);
    }

    @Transactional(readOnly = true)
    public Page<Alert> assignedTo(String analyst, Pageable pageable) {
        return alertRepository.findByAssignedToAndStatusIn(analyst, OPEN_STATUSES, pageable);
    }

    @Transactional(readOnly = true)
    public Page<Alert> byTypology(AmlTypology typology, Pageable pageable) {
        return alertRepository.findByTypologyOrderByRiskScoreDesc(typology, pageable);
    }

    /** Loads the alert with its triggered rules and evidence for the detail view. */
    @Transactional(readOnly = true)
    public Alert requireDetail(Long id) {
        return alertRepository
                .findWithDetailById(id)
                .orElseThrow(() -> NotFoundException.of("Alert", id));
    }

    @Transactional(readOnly = true)
    public Alert requireById(Long id) {
        return alertRepository
                .findWithCustomerById(id)
                .orElseThrow(() -> NotFoundException.of("Alert", id));
    }

    @Transactional(readOnly = true)
    public Page<Alert> forCustomer(String customerId, Pageable pageable) {
        return alertRepository.findByCustomer_CustomerIdOrderByFirstDetectedAtDesc(
                customerId, pageable);
    }

    @Transactional
    public Alert assign(Long id, String assignee) {
        Alert alert = requireById(id);
        if (alert.getStatus().isTerminal()) {
            throw new BusinessRuleException(
                    "Alert " + alert.getAlertReference() + " is closed and cannot be reassigned");
        }
        AlertStatus previous = alert.getStatus();
        String previousAssignee = alert.getAssignedTo();
        alert.setAssignedTo(assignee);
        alert.setAssignedAt(Instant.now());
        if (previous == AlertStatus.NEW) {
            alert.setStatus(AlertStatus.ASSIGNED);
        }
        Alert saved = alertRepository.save(alert);
        auditService.recordTransition(
                ENTITY,
                saved.getAlertReference(),
                previousAssignee == null ? AuditAction.ASSIGNED : AuditAction.REASSIGNED,
                previous,
                saved.getStatus(),
                AuditService.detailsOf(
                        "assignedTo", assignee, "previousAssignee", previousAssignee));
        return saved;
    }

    @Transactional
    public Alert changeStatus(Long id, AlertStatus target) {
        if (target == AlertStatus.CLOSED) {
            throw new BusinessRuleException(
                    "Closing an alert requires a disposition; use the disposition endpoint");
        }
        Alert alert = requireById(id);
        AlertStatus previous = alert.getStatus();
        if (previous == target) {
            return alert;
        }
        if (previous.isTerminal()) {
            throw new BusinessRuleException(
                    "Alert " + alert.getAlertReference() + " is closed and cannot be reopened");
        }
        alert.setStatus(target);
        Alert saved = alertRepository.save(alert);
        auditService.recordTransition(
                ENTITY,
                saved.getAlertReference(),
                target == AlertStatus.ESCALATED ? AuditAction.ESCALATED : AuditAction.STATUS_CHANGED,
                previous,
                target,
                null);
        return saved;
    }

    /**
     * Closes an alert with a disposition. Nothing is removed: the alert, its evidence, the reason
     * and the deciding analyst all remain queryable afterwards (business rule 6).
     */
    @Transactional
    public Alert disposition(Long id, AlertDisposition disposition, String reason) {
        if (disposition == null) {
            throw new BusinessRuleException("A disposition is required to close an alert");
        }
        if (reason == null || reason.isBlank()) {
            throw new BusinessRuleException(
                    "A disposition reason is required so the decision remains auditable");
        }
        Alert alert = requireById(id);
        if (alert.getStatus().isTerminal()) {
            throw new BusinessRuleException(
                    "Alert " + alert.getAlertReference() + " has already been closed");
        }
        AlertStatus previous = alert.getStatus();
        String analyst = CurrentUser.username();

        alert.setDisposition(disposition);
        alert.setDispositionReason(reason);
        alert.setDispositionBy(analyst);
        alert.setDispositionAt(Instant.now());
        alert.setStatus(AlertStatus.CLOSED);
        Alert saved = alertRepository.save(alert);

        auditService.recordTransition(
                ENTITY,
                saved.getAlertReference(),
                AuditAction.DISPOSITIONED,
                previous,
                AlertStatus.CLOSED,
                AuditService.detailsOf(
                        "disposition", disposition, "reason", reason, "analyst", analyst));
        log.info("Alert {} closed as {} by {}", saved.getAlertReference(), disposition, analyst);
        return saved;
    }

    @Transactional(readOnly = true)
    public long countOpen() {
        return alertRepository.countByStatusIn(OPEN_STATUSES);
    }

    public static Set<AlertStatus> openStatuses() {
        return OPEN_STATUSES;
    }

    /**
     * Result of a detection pass.
     *
     * @param created alerts inserted
     * @param aggregated detections folded into an alert that already existed
     */
    public record Outcome(int created, int aggregated) {

        public static final Outcome EMPTY = new Outcome(0, 0);

        public Outcome plus(Outcome other) {
            return new Outcome(created + other.created, aggregated + other.aggregated);
        }

        public int total() {
            return created + aggregated;
        }
    }
}
