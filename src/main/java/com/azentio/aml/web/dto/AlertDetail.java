package com.azentio.aml.web.dto;

import com.azentio.aml.domain.Alert;
import com.azentio.aml.domain.enums.AlertDisposition;
import com.azentio.aml.domain.enums.AlertSeverity;
import com.azentio.aml.domain.enums.AlertStatus;
import com.azentio.aml.domain.enums.AmlTypology;
import com.azentio.aml.security.PiiMasker;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * The full alert: why it fired, what it fired on, and what has been decided about it.
 *
 * <p>This is the payload behind the CCO's "explain WHY it flagged something". {@code explanation}
 * is the plain-English narrative, {@code triggeredRules} the machine-readable version with the
 * thresholds that were applied, and {@code evidence} the transactions an analyst can go and check.
 *
 * <p>The disposition fields remain populated after closure, since a cleared alert must stay
 * queryable with its reason and the deciding analyst (business rule 6).
 */
@Schema(name = "AlertDetail", description = "Full alert with evidence and triggered rules")
public record AlertDetail(
        Long id,
        String alertReference,
        String customerId,
        String customerName,
        String accountId,
        AmlTypology typology,
        AlertStatus status,
        AlertSeverity severity,
        Integer riskScore,
        String title,
        @Schema(description = "Human-readable reason the pattern was flagged") String explanation,
        Integer occurrenceCount,
        Instant detectionWindowStart,
        Instant detectionWindowEnd,
        Instant firstDetectedAt,
        Instant lastDetectedAt,
        BigDecimal totalAmount,
        Integer transactionCount,
        String assignedTo,
        Instant assignedAt,
        String caseNumber,
        AlertDisposition disposition,
        String dispositionReason,
        String dispositionBy,
        Instant dispositionAt,
        List<TriggeredRuleView> triggeredRules,
        List<EvidenceView> evidence,
        boolean piiUnmasked) {

    public static AlertDetail from(Alert alert, boolean unmasked) {
        List<TriggeredRuleView> rules =
                alert.getTriggeredRules().stream().map(TriggeredRuleView::from).toList();
        List<EvidenceView> evidence =
                alert.getEvidence().stream()
                        .map(EvidenceView::from)
                        // Chronological order so the pattern reads as the story it describes.
                        .sorted(
                                Comparator.comparing(
                                        EvidenceView::occurredAt,
                                        Comparator.nullsLast(Comparator.naturalOrder())))
                        .toList();
        return new AlertDetail(
                alert.getId(),
                alert.getAlertReference(),
                alert.getCustomer().getCustomerId(),
                PiiMasker.apply(
                        alert.getCustomer().getFullName(), unmasked, PiiMasker::maskName),
                alert.getAccount() == null ? null : alert.getAccount().getAccountId(),
                alert.getTypology(),
                alert.getStatus(),
                alert.getSeverity(),
                alert.getRiskScore(),
                alert.getTitle(),
                alert.getExplanation(),
                alert.getOccurrenceCount(),
                alert.getDetectionWindowStart(),
                alert.getDetectionWindowEnd(),
                alert.getFirstDetectedAt(),
                alert.getLastDetectedAt(),
                alert.getTotalAmount(),
                alert.getTransactionCount(),
                alert.getAssignedTo(),
                alert.getAssignedAt(),
                alert.getAmlCase() == null ? null : alert.getAmlCase().getCaseNumber(),
                alert.getDisposition(),
                alert.getDispositionReason(),
                alert.getDispositionBy(),
                alert.getDispositionAt(),
                rules,
                evidence,
                unmasked);
    }
}
