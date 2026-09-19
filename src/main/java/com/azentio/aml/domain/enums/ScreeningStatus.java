package com.azentio.aml.domain.enums;

/** Detection engine bookkeeping so a transaction is scored exactly once per ingestion. */
public enum ScreeningStatus {
    PENDING,
    IN_PROGRESS,
    SCREENED,
    FAILED,
    SKIPPED
}
