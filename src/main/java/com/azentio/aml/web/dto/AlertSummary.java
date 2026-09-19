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

/**
 * An alert as it appears in the analyst queue.
 *
 * <p>Carries the risk score and the one-line title so the queue is triageable without opening every
 * alert, and the customer name is masked because the queue is a list view (business rule 8).
 *
 * <p>{@code occurrenceCount} is exposed deliberately: it is the visible proof that de-duplication
 * is working - one row saying "fired 14 times" instead of fourteen rows.
 */
@Schema(name = "AlertSummary", description = "Alert queue entry, highest risk first")
public record AlertSummary(
        Long id,
        @Schema(example = "ALT-2026-000123") String alertReference,
        String customerId,
        @Schema(example = "K****** S*****") String customerName,
        String accountId,
        AmlTypology typology,
        AlertStatus status,
        AlertSeverity severity,
        @Schema(example = "78") Integer riskScore,
        String title,
        Integer occurrenceCount,
        BigDecimal totalAmount,
        Integer transactionCount,
        Instant firstDetectedAt,
        Instant lastDetectedAt,
        String assignedTo,
        AlertDisposition disposition,
        @Schema(description = "Case number when the alert has been escalated into an investigation")
                String caseNumber) {

    public static AlertSummary from(Alert alert) {
        return new AlertSummary(
                alert.getId(),
                alert.getAlertReference(),
                alert.getCustomer().getCustomerId(),
                PiiMasker.maskName(alert.getCustomer().getFullName()),
                alert.getAccount() == null ? null : alert.getAccount().getAccountId(),
                alert.getTypology(),
                alert.getStatus(),
                alert.getSeverity(),
                alert.getRiskScore(),
                alert.getTitle(),
                alert.getOccurrenceCount(),
                alert.getTotalAmount(),
                alert.getTransactionCount(),
                alert.getFirstDetectedAt(),
                alert.getLastDetectedAt(),
                alert.getAssignedTo(),
                alert.getDisposition(),
                alert.getAmlCase() == null ? null : alert.getAmlCase().getCaseNumber());
    }
}
