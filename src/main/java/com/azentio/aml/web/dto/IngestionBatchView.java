package com.azentio.aml.web.dto;

import com.azentio.aml.domain.IngestionBatch;
import com.azentio.aml.domain.enums.IngestionEntityType;
import com.azentio.aml.domain.enums.IngestionSource;
import com.azentio.aml.domain.enums.IngestionStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/** History of a load, so an operator can confirm what was accepted and what was rejected. */
@Schema(name = "IngestionBatchView", description = "An ingestion batch and its counters")
public record IngestionBatchView(
        Long id,
        @Schema(example = "BATCH-2026-1A2B3C45") String batchReference,
        IngestionSource source,
        IngestionEntityType entityType,
        String fileName,
        IngestionStatus status,
        Integer totalRecords,
        Integer successCount,
        Integer failureCount,
        Integer duplicateCount,
        String errorSummary,
        String submittedBy,
        Instant startedAt,
        Instant completedAt) {

    public static IngestionBatchView from(IngestionBatch batch) {
        return new IngestionBatchView(
                batch.getId(),
                batch.getBatchReference(),
                batch.getSource(),
                batch.getEntityType(),
                batch.getFileName(),
                batch.getStatus(),
                batch.getTotalRecords(),
                batch.getSuccessCount(),
                batch.getFailureCount(),
                batch.getDuplicateCount(),
                batch.getErrorSummary(),
                batch.getSubmittedBy(),
                batch.getStartedAt(),
                batch.getCompletedAt());
    }
}
