package com.azentio.aml.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Closes an investigation with a mandatory, auditable reason. */
@Schema(name = "CloseCaseRequest", description = "Closure of an investigation")
public record CloseCaseRequest(
        @NotBlank(message = "a closure reason is required for the audit trail") @Size(max = 4000)
                String closureReason,
        @Size(max = 20000)
                @Schema(description = "Optional final narrative; the drafted SAR text if omitted")
                String narrative) {}
