package com.azentio.aml.web.dto;

import com.azentio.aml.domain.AmlCase;
import com.azentio.aml.domain.enums.CasePriority;
import com.azentio.aml.domain.enums.CaseStatus;
import com.azentio.aml.security.PiiMasker;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * The complete case file: the alerts under investigation, the analyst's notes, the SAR narrative
 * and the closure record.
 *
 * <p>Everything needed to answer "what did we know, when did we know it, and who decided what" is
 * in this one payload, which is exactly the question an audit asks.
 */
@Schema(name = "CaseDetail", description = "Full case file with linked alerts and notes")
public record CaseDetail(
        Long id,
        String caseNumber,
        String title,
        String description,
        String customerId,
        String customerName,
        CaseStatus status,
        CasePriority priority,
        Integer aggregateRiskScore,
        BigDecimal totalExposureAmount,
        String assignedTo,
        Instant assignedAt,
        String openedBy,
        Instant openedAt,
        Instant dueAt,
        Instant closedAt,
        String closedBy,
        String closureReason,
        @Schema(description = "Draft or filed SAR narrative summarising the evidence")
                String narrative,
        boolean sarFiled,
        String sarReference,
        Instant sarFiledAt,
        List<AlertSummary> alerts,
        List<CaseNoteView> notes,
        boolean piiUnmasked) {

    public static CaseDetail from(
            AmlCase amlCase,
            List<AlertSummary> alerts,
            List<CaseNoteView> notes,
            boolean unmasked) {
        return new CaseDetail(
                amlCase.getId(),
                amlCase.getCaseNumber(),
                amlCase.getTitle(),
                amlCase.getDescription(),
                amlCase.getCustomer().getCustomerId(),
                PiiMasker.apply(
                        amlCase.getCustomer().getFullName(), unmasked, PiiMasker::maskName),
                amlCase.getStatus(),
                amlCase.getPriority(),
                amlCase.getAggregateRiskScore(),
                amlCase.getTotalExposureAmount(),
                amlCase.getAssignedTo(),
                amlCase.getAssignedAt(),
                amlCase.getOpenedBy(),
                amlCase.getOpenedAt(),
                amlCase.getDueAt(),
                amlCase.getClosedAt(),
                amlCase.getClosedBy(),
                amlCase.getClosureReason(),
                amlCase.getNarrative(),
                Boolean.TRUE.equals(amlCase.getSarFiled()),
                amlCase.getSarReference(),
                amlCase.getSarFiledAt(),
                alerts,
                notes,
                unmasked);
    }
}
