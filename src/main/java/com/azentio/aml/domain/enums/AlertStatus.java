package com.azentio.aml.domain.enums;

/**
 * Alert workflow state. Alerts are never deleted; they terminate in {@link #CLOSED} with a
 * recorded disposition for audit purposes.
 */
public enum AlertStatus {
    NEW,
    ASSIGNED,
    IN_REVIEW,
    PENDING_INFO,
    ESCALATED,
    CLOSED;

    public boolean isTerminal() {
        return this == CLOSED;
    }
}
