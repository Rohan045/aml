package com.azentio.aml.web.dto;

import com.azentio.aml.domain.IngestionError;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/** A row a batch could not accept, with enough context for the operator to correct and resubmit. */
@Schema(name = "IngestionErrorView", description = "A rejected ingestion record")
public record IngestionErrorView(
        Long id,
        @Schema(description = "Position of the row in the submitted file or batch")
                Integer recordNumber,
        @Schema(description = "Business identifier of the record, when it could be read")
                String recordIdentifier,
        String fieldName,
        @Schema(example = "UNKNOWN_ACCOUNT") String errorCode,
        String errorMessage,
        String rawRecord,
        Instant occurredAt) {

    public static IngestionErrorView from(IngestionError error) {
        return new IngestionErrorView(
                error.getId(),
                error.getRecordNumber(),
                error.getRecordIdentifier(),
                error.getFieldName(),
                error.getErrorCode(),
                error.getErrorMessage(),
                error.getRawRecord(),
                error.getOccurredAt());
    }
}
