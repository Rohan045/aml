package com.azentio.aml.service;

import com.azentio.aml.common.Format;
import com.azentio.aml.common.exception.BusinessRuleException;
import com.azentio.aml.common.exception.NotFoundException;
import com.azentio.aml.domain.Alert;
import com.azentio.aml.domain.AlertEvidence;
import com.azentio.aml.domain.AmlCase;
import com.azentio.aml.domain.CaseNote;
import com.azentio.aml.domain.Customer;
import com.azentio.aml.domain.enums.AlertStatus;
import com.azentio.aml.domain.enums.AuditAction;
import com.azentio.aml.domain.enums.CasePriority;
import com.azentio.aml.domain.enums.CaseStatus;
import com.azentio.aml.repository.AlertRepository;
import com.azentio.aml.repository.AmlCaseRepository;
import com.azentio.aml.repository.CaseNoteRepository;
import com.azentio.aml.repository.CustomerRepository;
import com.azentio.aml.security.CurrentUser;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Investigation case management: the workflow an analyst actually works in.
 *
 * <p>A case is the unit of investigation, an alert the unit of detection. Several alerts on one
 * customer - structuring on Monday, a sanctioned counterparty on Wednesday - are one story and
 * belong in one case; forcing an analyst to disposition them separately is exactly the fragmented
 * process the bank is replacing.
 *
 * <p>Case priority and aggregate risk are derived from the linked alerts rather than entered by
 * hand, so the queue ordering cannot drift away from the evidence. As with alerts, cases are never
 * deleted: closure records the reason, the closer and the time.
 */
@Service
public class CaseService {

    private static final Logger log = LoggerFactory.getLogger(CaseService.class);
    private static final String ENTITY = "Case";
    private static final List<CaseStatus> OPEN_STATUSES =
            List.of(
                    CaseStatus.OPEN,
                    CaseStatus.ASSIGNED,
                    CaseStatus.INVESTIGATING,
                    CaseStatus.PENDING_REVIEW,
                    CaseStatus.ESCALATED,
                    CaseStatus.SAR_FILED);

    /** Regulatory clock for an investigation, in days, used to set the case due date. */
    private static final int INVESTIGATION_SLA_DAYS = 30;

    private final AmlCaseRepository caseRepository;
    private final AlertRepository alertRepository;
    private final CaseNoteRepository caseNoteRepository;
    private final CustomerRepository customerRepository;
    private final AuditService auditService;

    public CaseService(
            AmlCaseRepository caseRepository,
            AlertRepository alertRepository,
            CaseNoteRepository caseNoteRepository,
            CustomerRepository customerRepository,
            AuditService auditService) {
        this.caseRepository = caseRepository;
        this.alertRepository = alertRepository;
        this.caseNoteRepository = caseNoteRepository;
        this.customerRepository = customerRepository;
        this.auditService = auditService;
    }

    // ------------------------------------------------------------------
    // Creation and linkage
    // ------------------------------------------------------------------

    /**
     * Opens a case over one or more alerts belonging to the same customer.
     *
     * @throws BusinessRuleException if the alerts span more than one customer - a case is always
     *     about a single subject, and silently splitting or merging them would corrupt the
     *     investigation record
     */
    @Transactional
    public AmlCase open(String title, String description, List<Long> alertIds) {
        if (alertIds == null || alertIds.isEmpty()) {
            throw new BusinessRuleException("A case must be opened over at least one alert");
        }
        List<Alert> alerts = new ArrayList<>();
        for (Long alertId : alertIds) {
            alerts.add(
                    alertRepository
                            .findById(alertId)
                            .orElseThrow(() -> NotFoundException.of("Alert", alertId)));
        }
        Customer customer = alerts.get(0).getCustomer();
        for (Alert alert : alerts) {
            if (!alert.getCustomer().getCustomerId().equals(customer.getCustomerId())) {
                throw new BusinessRuleException(
                        "All alerts in a case must belong to the same customer");
            }
            if (alert.getAmlCase() != null) {
                throw new BusinessRuleException(
                        "Alert "
                                + alert.getAlertReference()
                                + " is already linked to case "
                                + alert.getAmlCase().getCaseNumber());
            }
        }

        String actor = CurrentUser.username();
        Instant now = Instant.now();
        AmlCase amlCase =
                AmlCase.builder()
                        .caseNumber(nextCaseNumber())
                        .title(title)
                        .description(description)
                        .customer(customer)
                        .status(CaseStatus.OPEN)
                        .openedBy(actor)
                        .openedAt(now)
                        .dueAt(now.plus(INVESTIGATION_SLA_DAYS, ChronoUnit.DAYS))
                        .build();

        for (Alert alert : alerts) {
            amlCase.linkAlert(alert);
            alert.setStatus(AlertStatus.ESCALATED);
        }
        recomputeAggregates(amlCase);

        AmlCase saved = caseRepository.save(amlCase);
        auditService.record(
                ENTITY,
                saved.getCaseNumber(),
                AuditAction.CREATED,
                null,
                CaseStatus.OPEN.name(),
                AuditService.detailsOf(
                        "customerId", customer.getCustomerId(),
                        "alerts", alertIds.size(),
                        "aggregateRiskScore", saved.getAggregateRiskScore(),
                        "priority", saved.getPriority()));
        for (Alert alert : alerts) {
            auditService.record(
                    "Alert",
                    alert.getAlertReference(),
                    AuditAction.ESCALATED,
                    AuditService.detailsOf("caseNumber", saved.getCaseNumber()));
        }
        log.info(
                "Opened case {} over {} alerts for customer {}",
                saved.getCaseNumber(),
                alerts.size(),
                customer.getCustomerId());
        return saved;
    }

    /** Attaches a further alert to an existing investigation. */
    @Transactional
    public AmlCase linkAlert(Long caseId, Long alertId) {
        AmlCase amlCase = requireById(caseId);
        if (amlCase.getStatus().isTerminal()) {
            throw new BusinessRuleException(
                    "Case " + amlCase.getCaseNumber() + " is closed and cannot take new alerts");
        }
        Alert alert =
                alertRepository
                        .findById(alertId)
                        .orElseThrow(() -> NotFoundException.of("Alert", alertId));
        if (!alert.getCustomer()
                .getCustomerId()
                .equals(amlCase.getCustomer().getCustomerId())) {
            throw new BusinessRuleException(
                    "Alert " + alert.getAlertReference() + " belongs to a different customer");
        }
        if (alert.getAmlCase() != null) {
            throw new BusinessRuleException(
                    "Alert " + alert.getAlertReference() + " is already linked to a case");
        }
        amlCase.linkAlert(alert);
        alert.setStatus(AlertStatus.ESCALATED);
        recomputeAggregates(amlCase);
        AmlCase saved = caseRepository.save(amlCase);

        auditService.record(
                ENTITY,
                saved.getCaseNumber(),
                AuditAction.UPDATED,
                AuditService.detailsOf(
                        "linkedAlert", alert.getAlertReference(),
                        "aggregateRiskScore", saved.getAggregateRiskScore()));
        return saved;
    }

    // ------------------------------------------------------------------
    // Workflow
    // ------------------------------------------------------------------

    @Transactional
    public AmlCase assign(Long caseId, String assignee) {
        AmlCase amlCase = requireById(caseId);
        if (amlCase.getStatus().isTerminal()) {
            throw new BusinessRuleException(
                    "Case " + amlCase.getCaseNumber() + " is closed and cannot be reassigned");
        }
        CaseStatus previous = amlCase.getStatus();
        String previousAssignee = amlCase.getAssignedTo();
        amlCase.setAssignedTo(assignee);
        amlCase.setAssignedAt(Instant.now());
        if (previous == CaseStatus.OPEN) {
            amlCase.setStatus(CaseStatus.ASSIGNED);
        }
        AmlCase saved = caseRepository.save(amlCase);
        auditService.recordTransition(
                ENTITY,
                saved.getCaseNumber(),
                previousAssignee == null ? AuditAction.ASSIGNED : AuditAction.REASSIGNED,
                previous,
                saved.getStatus(),
                AuditService.detailsOf(
                        "assignedTo", assignee, "previousAssignee", previousAssignee));
        return saved;
    }

    @Transactional
    public AmlCase changeStatus(Long caseId, CaseStatus target) {
        if (target == CaseStatus.CLOSED) {
            throw new BusinessRuleException(
                    "Closing a case requires a closure reason; use the close endpoint");
        }
        AmlCase amlCase = requireById(caseId);
        CaseStatus previous = amlCase.getStatus();
        if (previous == target) {
            return amlCase;
        }
        if (previous.isTerminal()) {
            throw new BusinessRuleException(
                    "Case " + amlCase.getCaseNumber() + " is closed and cannot be reopened");
        }
        amlCase.setStatus(target);
        AmlCase saved = caseRepository.save(amlCase);
        auditService.recordTransition(
                ENTITY,
                saved.getCaseNumber(),
                target == CaseStatus.ESCALATED ? AuditAction.ESCALATED : AuditAction.STATUS_CHANGED,
                previous,
                target,
                null);
        return saved;
    }

    @Transactional
    public CaseNote addNote(Long caseId, String note) {
        if (note == null || note.isBlank()) {
            throw new BusinessRuleException("A case note cannot be empty");
        }
        AmlCase amlCase = requireById(caseId);
        CaseNote caseNote =
                CaseNote.builder()
                        .author(CurrentUser.username())
                        .note(note)
                        .noteCreatedAt(Instant.now())
                        .build();
        amlCase.addNote(caseNote);
        caseRepository.save(amlCase);
        auditService.record(
                ENTITY,
                amlCase.getCaseNumber(),
                AuditAction.NOTE_ADDED,
                AuditService.detailsOf("author", caseNote.getAuthor()));
        return caseNote;
    }

    /**
     * Closes an investigation. The case, its alerts, notes and evidence all remain in the system;
     * only the status changes (business rule 6).
     */
    @Transactional
    public AmlCase close(Long caseId, String closureReason, String narrative) {
        if (closureReason == null || closureReason.isBlank()) {
            throw new BusinessRuleException(
                    "A closure reason is required so the decision remains auditable");
        }
        AmlCase amlCase = requireById(caseId);
        if (amlCase.getStatus().isTerminal()) {
            throw new BusinessRuleException(
                    "Case " + amlCase.getCaseNumber() + " is already closed");
        }
        CaseStatus previous = amlCase.getStatus();
        String actor = CurrentUser.username();

        amlCase.setStatus(CaseStatus.CLOSED);
        amlCase.setClosureReason(closureReason);
        amlCase.setClosedBy(actor);
        amlCase.setClosedAt(Instant.now());
        if (narrative != null && !narrative.isBlank()) {
            amlCase.setNarrative(narrative);
        }
        AmlCase saved = caseRepository.save(amlCase);

        auditService.recordTransition(
                ENTITY,
                saved.getCaseNumber(),
                AuditAction.CLOSED,
                previous,
                CaseStatus.CLOSED,
                AuditService.detailsOf("closureReason", closureReason, "closedBy", actor));
        log.info("Case {} closed by {}", saved.getCaseNumber(), actor);
        return saved;
    }

    /** Records that a Suspicious Activity Report was filed with the FIU. */
    @Transactional
    public AmlCase fileSar(Long caseId, String sarReference) {
        if (sarReference == null || sarReference.isBlank()) {
            throw new BusinessRuleException("The regulator's SAR reference is required");
        }
        AmlCase amlCase = requireById(caseId);
        if (Boolean.TRUE.equals(amlCase.getSarFiled())) {
            throw new BusinessRuleException(
                    "Case "
                            + amlCase.getCaseNumber()
                            + " already has SAR "
                            + amlCase.getSarReference()
                            + " on file");
        }
        CaseStatus previous = amlCase.getStatus();
        amlCase.setSarFiled(Boolean.TRUE);
        amlCase.setSarReference(sarReference);
        amlCase.setSarFiledAt(Instant.now());
        amlCase.setStatus(CaseStatus.SAR_FILED);
        if (amlCase.getNarrative() == null || amlCase.getNarrative().isBlank()) {
            amlCase.setNarrative(draftSarNarrative(amlCase));
        }
        AmlCase saved = caseRepository.save(amlCase);

        auditService.recordTransition(
                ENTITY,
                saved.getCaseNumber(),
                AuditAction.SAR_FILED,
                previous,
                CaseStatus.SAR_FILED,
                AuditService.detailsOf(
                        "sarReference", sarReference, "filedBy", CurrentUser.username()));
        return saved;
    }

    /**
     * Assembles a SAR draft from the evidence already on the case.
     *
     * <p>Deliberately a draft, not a filing: it saves the analyst re-typing figures they have
     * already reviewed, while the narrative and the decision to file remain entirely theirs.
     */
    @Transactional(readOnly = true)
    public String draftSarNarrative(Long caseId) {
        return draftSarNarrative(requireById(caseId));
    }

    private String draftSarNarrative(AmlCase amlCase) {
        Customer customer = amlCase.getCustomer();
        List<Alert> alerts = alertRepository.findByAmlCase_Id(amlCase.getId());

        StringBuilder narrative = new StringBuilder();
        narrative
                .append("SUSPICIOUS ACTIVITY REPORT - DRAFT\n")
                .append("Case reference: ")
                .append(amlCase.getCaseNumber())
                .append("\nSubject: customer ")
                .append(customer.getCustomerId())
                .append(" (")
                .append(customer.getCustomerSegment())
                .append(" segment, ")
                .append(customer.getRiskRating())
                .append(" risk rating")
                .append(Boolean.TRUE.equals(customer.getPoliticallyExposed())
                        ? ", politically exposed"
                        : "")
                .append(")\nOpened: ")
                .append(Format.timestamp(amlCase.getOpenedAt()))
                .append("\nAggregate risk score: ")
                .append(amlCase.getAggregateRiskScore())
                .append("/100\n\nGROUNDS FOR SUSPICION\n");

        int index = 1;
        for (Alert alert : alerts) {
            narrative
                    .append(index++)
                    .append(". ")
                    .append(alert.getTypology())
                    .append(" - ")
                    .append(alert.getTitle())
                    .append(" (alert ")
                    .append(alert.getAlertReference())
                    .append(", risk ")
                    .append(alert.getRiskScore())
                    .append("/100, detected ")
                    .append(Format.timestamp(alert.getFirstDetectedAt()))
                    .append(")\n")
                    .append(alert.getExplanation())
                    .append("\n\n");
        }

        narrative.append("SUPPORTING TRANSACTIONS\n");
        for (Alert alert : alerts) {
            for (AlertEvidence evidence : alert.getEvidence()) {
                narrative
                        .append("  - ")
                        .append(evidence.getExternalTxnId())
                        .append(" on ")
                        .append(Format.timestamp(evidence.getOccurredAt()))
                        .append(" for ")
                        .append(Format.money(evidence.getBaseAmount(), "USD"))
                        .append(" (")
                        .append(evidence.getEvidenceRole())
                        .append(")\n");
            }
        }
        narrative
                .append("\nTotal exposure under review: ")
                .append(Format.money(amlCase.getTotalExposureAmount(), "USD"))
                .append("\n\nPrepared by ")
                .append(CurrentUser.username())
                .append(" on ")
                .append(Format.timestamp(Instant.now()))
                .append(". This draft must be reviewed and completed by the "
                        + "compliance officer before filing.");
        return narrative.toString();
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public AmlCase requireById(Long caseId) {
        return caseRepository
                .findById(caseId)
                .orElseThrow(() -> NotFoundException.of("Case", caseId));
    }

    @Transactional(readOnly = true)
    public Page<AmlCase> queue(Collection<CaseStatus> statuses, Pageable pageable) {
        Collection<CaseStatus> effective =
                statuses == null || statuses.isEmpty() ? OPEN_STATUSES : statuses;
        return caseRepository.findByStatusIn(effective, pageable);
    }

    @Transactional(readOnly = true)
    public List<Alert> alertsOf(Long caseId) {
        return alertRepository.findByAmlCase_Id(caseId);
    }

    @Transactional(readOnly = true)
    public List<CaseNote> notesOf(Long caseId) {
        return caseNoteRepository.findByAmlCase_IdOrderByNoteCreatedAtAsc(caseId);
    }

    @Transactional(readOnly = true)
    public Page<AmlCase> forCustomer(String customerId, Pageable pageable) {
        if (!customerRepository.existsById(customerId)) {
            throw NotFoundException.of("Customer", customerId);
        }
        return caseRepository.findByCustomer_CustomerIdOrderByOpenedAtDesc(customerId, pageable);
    }

    @Transactional(readOnly = true)
    public long countOpen() {
        return caseRepository.countByStatusIn(OPEN_STATUSES);
    }

    public static List<CaseStatus> openStatuses() {
        return OPEN_STATUSES;
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /**
     * Derives case-level risk and exposure from the linked alerts.
     *
     * <p>The aggregate score is the highest linked alert's score, not a sum: a case containing one
     * critical alert and four minor ones is a critical case, and adding weak alerts to a strong one
     * must never be able to inflate its priority.
     */
    private void recomputeAggregates(AmlCase amlCase) {
        int highest = 0;
        BigDecimal exposure = BigDecimal.ZERO;
        for (Alert alert : amlCase.getAlerts()) {
            highest = Math.max(highest, alert.getRiskScore() == null ? 0 : alert.getRiskScore());
            if (alert.getTotalAmount() != null) {
                exposure = exposure.add(alert.getTotalAmount());
            }
        }
        amlCase.setAggregateRiskScore(highest);
        amlCase.setTotalExposureAmount(exposure);
        amlCase.setPriority(priorityFor(highest));
    }

    private CasePriority priorityFor(int riskScore) {
        if (riskScore >= 80) {
            return CasePriority.URGENT;
        }
        if (riskScore >= 60) {
            return CasePriority.HIGH;
        }
        if (riskScore >= 40) {
            return CasePriority.MEDIUM;
        }
        return CasePriority.LOW;
    }

    /** Mirrors the alert reference scheme: time-ordered, unique, and free of sequence contention. */
    private String nextCaseNumber() {
        Instant now = Instant.now();
        String suffix =
                Long.toString(now.toEpochMilli(), 36).toUpperCase()
                        + Integer.toString(1296 + ThreadLocalRandom.current().nextInt(1296), 36)
                                .toUpperCase()
                                .substring(1);
        return "CASE-" + now.atZone(ZoneOffset.UTC).getYear() + "-" + suffix;
    }
}
