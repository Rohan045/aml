package com.azentio.aml.web.dto;

import com.azentio.aml.domain.enums.CaseStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/** Moves a case through the investigation workflow. Closure has its own endpoint. */
@Schema(name = "CaseStatusRequest", description = "Case workflow transition")
public record CaseStatusRequest(
        @NotNull(message = "a target status is required")
                @Schema(
                        description = "Target status. Use the close endpoint to close a case.",
                        allowableValues = {
                            "OPEN",
                            "ASSIGNED",
                            "INVESTIGATING",
                            "PENDING_REVIEW",
                            "ESCALATED"
                        })
                CaseStatus status) {}
