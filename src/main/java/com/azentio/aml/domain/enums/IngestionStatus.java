package com.azentio.aml.domain.enums;

public enum IngestionStatus {
    RECEIVED,
    VALIDATING,
    PROCESSING,
    COMPLETED,
    COMPLETED_WITH_ERRORS,
    FAILED
}
