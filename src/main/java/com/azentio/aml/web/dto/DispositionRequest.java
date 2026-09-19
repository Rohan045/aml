package com.azentio.aml.web.dto;

import com.azentio.aml.domain.enums.AlertDisposition;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Closes an alert with a decision.
 *
 * <p>The reason is mandatory at the API boundary as well as in the service: a cleared alert whose
 * justification was never recorded is indistinguishable, months later, from one that was missed.
 */
@Schema(name = "DispositionRequest", description = "Closure decision for an alert")
public record DispositionRequest(
        @NotNull(message = "a disposition is required") AlertDisposition disposition,
        @NotBlank(message = "a disposition reason is required for the audit trail")
                @Size(max = 4000)
                @Schema(
                        example =
                                "Salary credits from a verified employer; pattern explained by"
                                        + " payroll cycle.")
                String reason) {}
