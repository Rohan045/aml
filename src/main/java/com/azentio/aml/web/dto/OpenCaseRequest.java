package com.azentio.aml.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Opens an investigation over one or more alerts, which must all concern the same customer. */
@Schema(name = "OpenCaseRequest", description = "Opens a case over existing alerts")
public record OpenCaseRequest(
        @NotBlank(message = "a case title is required") @Size(max = 200) String title,
        @Size(max = 4000) String description,
        @NotEmpty(message = "a case must be opened over at least one alert")
                @Schema(description = "Alert ids to investigate together")
                List<Long> alertIds) {}
