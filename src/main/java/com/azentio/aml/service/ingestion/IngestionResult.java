package com.azentio.aml.service.ingestion;

import com.azentio.aml.domain.enums.IngestionEntityType;
import com.azentio.aml.domain.enums.IngestionStatus;
import java.time.Instant;
import java.util.List;

/**
 * Outcome of an ingestion batch, returned synchronously to the caller.
 *
 * <p>A batch never fails as a whole because one record is malformed: good records are persisted and
 * bad ones are reported here and in {@code ingestion_errors}, so an operator can fix and re-submit
 * exactly the rows that failed instead of the entire file.
 *
 * @param duplicates records skipped because the external transaction id was already stored -
 *     expected during replays, not an error
 * @param alertsCreated alerts raised by the detection run triggered from this batch
 */
public record IngestionResult(
        String batchReference,
        IngestionEntityType entityType,
        IngestionStatus status,
        int totalRecords,
        int succeeded,
        int failed,
        int duplicates,
        long alertsCreated,
        long alertsAggregated,
        Instant startedAt,
        Instant completedAt,
        List<RecordError> errors) {

    /** A single rejected record, identified well enough for the operator to locate it in the feed. */
    public record RecordError(
            int recordNumber,
            String recordIdentifier,
            String fieldName,
            String errorCode,
            String errorMessage) {}

    public boolean hasErrors() {
        return failed > 0;
    }

    /** Milliseconds spent on the batch; the 10k-in-under-2-minutes budget is measured on this. */
    public long durationMillis() {
        return startedAt == null || completedAt == null
                ? 0L
                : completedAt.toEpochMilli() - startedAt.toEpochMilli();
    }
}
