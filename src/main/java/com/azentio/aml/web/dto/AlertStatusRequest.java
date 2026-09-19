package com.azentio.aml.web.dto;

import com.azentio.aml.domain.enums.AlertStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * Moves an alert through triage.
 *
 * <p>{@code CLOSED} is deliberately not reachable here: closing requires a disposition and a
 * reason, so it has its own endpoint (business rule 6).
 */
@Schema(name = "AlertStatusRequest", description = "Alert triage transition")
public record AlertStatusRequest(
        @NotNull(message = "a target status is required")
                @Schema(
                        description =
                                "Target status. Use the disposition endpoint to close an alert.",
                        allowableValues = {
                            "NEW",
                            "ASSIGNED",
                            "IN_REVIEW",
                            "PENDING_INFO",
                            "ESCALATED"
                        })
                AlertStatus status) {}
