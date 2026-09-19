package com.azentio.aml.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Records that a Suspicious Activity Report was filed with the Financial Intelligence Unit. */
@Schema(name = "SarFilingRequest", description = "SAR filing acknowledgement")
public record SarFilingRequest(
        @NotBlank(message = "the regulator's SAR reference is required") @Size(max = 64)
                @Schema(example = "FIU-IND-2026-004411")
                String sarReference) {}
