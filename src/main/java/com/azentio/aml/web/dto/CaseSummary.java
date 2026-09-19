package com.azentio.aml.web.dto;

import com.azentio.aml.domain.AmlCase;
import com.azentio.aml.domain.enums.CasePriority;
import com.azentio.aml.domain.enums.CaseStatus;
import com.azentio.aml.security.PiiMasker;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;

/** An investigation as it appears in the case queue. Customer name masked, as for alerts. */
@Schema(name = "CaseSummary", description = "Case queue entry")
public record CaseSummary(
        Long id,
        @Schema(example = "CASE-2026-000045") String caseNumber,
        String title,
        String customerId,
        String customerName,
        CaseStatus status,
        CasePriority priority,
        @Schema(description = "Highest risk score across the linked alerts")
                Integer aggregateRiskScore,
        BigDecimal totalExposureAmount,
        String assignedTo,
        Instant openedAt,
        @Schema(description = "SLA deadline for disposition") Instant dueAt,
        Instant closedAt,
        boolean sarFiled) {

    public static CaseSummary from(AmlCase amlCase) {
        return new CaseSummary(
                amlCase.getId(),
                amlCase.getCaseNumber(),
                amlCase.getTitle(),
                amlCase.getCustomer().getCustomerId(),
                PiiMasker.maskName(amlCase.getCustomer().getFullName()),
                amlCase.getStatus(),
                amlCase.getPriority(),
                amlCase.getAggregateRiskScore(),
                amlCase.getTotalExposureAmount(),
                amlCase.getAssignedTo(),
                amlCase.getOpenedAt(),
                amlCase.getDueAt(),
                amlCase.getClosedAt(),
                Boolean.TRUE.equals(amlCase.getSarFiled()));
    }
}
