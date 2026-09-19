package com.azentio.aml.domain.enums;

/** Where an ingestion batch originated. */
public enum IngestionSource {
    CSV_UPLOAD,
    REST_BATCH,
    REST_SINGLE,
    KAFKA_STREAM,
    SEED_DATA
}
