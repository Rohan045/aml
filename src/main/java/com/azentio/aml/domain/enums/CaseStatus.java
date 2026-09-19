package com.azentio.aml.domain.enums;

public enum CaseStatus {
    OPEN,
    ASSIGNED,
    INVESTIGATING,
    PENDING_REVIEW,
    ESCALATED,
    SAR_FILED,
    CLOSED;

    public boolean isTerminal() {
        return this == CLOSED;
    }
}
