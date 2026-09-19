package com.azentio.aml.domain.enums;

/** Immutable audit trail verbs for alert / case / rule state transitions. */
public enum AuditAction {
    CREATED,
    UPDATED,
    STATUS_CHANGED,
    ASSIGNED,
    REASSIGNED,
    ESCALATED,
    DISPOSITIONED,
    CLOSED,
    REOPENED,
    NOTE_ADDED,
    RULE_ENABLED,
    RULE_DISABLED,
    RULE_TUNED,
    PII_REVEALED,
    INGESTION_STARTED,
    INGESTION_COMPLETED,
    SAR_DRAFTED,
    SAR_FILED,
    LOGIN
}
