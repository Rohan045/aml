package com.azentio.aml.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Assigns an alert or a case to a named analyst. */
@Schema(name = "AssignRequest", description = "Assignment of an alert or case to an analyst")
public record AssignRequest(
        @NotBlank(message = "an assignee is required") @Size(max = 100)
                @Schema(example = "a.mehta", description = "Username of the receiving analyst")
                String assignee) {}
