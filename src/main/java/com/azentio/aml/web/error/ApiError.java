package com.azentio.aml.web.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.Map;

/**
 * The single error envelope returned by every endpoint.
 *
 * @param fieldErrors populated only for validation failures, keyed by field path
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "ApiError", description = "Standard error response returned by all endpoints")
public record ApiError(
        @Schema(example = "2026-03-14T09:15:00Z") Instant timestamp,
        @Schema(example = "400") int status,
        @Schema(example = "Validation failed") String error,
        @Schema(example = "One or more fields are invalid") String detail,
        @Schema(example = "/api/v1/alerts") String path,
        Map<String, String> fieldErrors) {}
