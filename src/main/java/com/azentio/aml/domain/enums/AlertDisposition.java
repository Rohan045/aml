package com.azentio.aml.domain.enums;

/** Analyst outcome recorded when an alert is closed. */
public enum AlertDisposition {
    TRUE_POSITIVE,
    FALSE_POSITIVE,
    ESCALATED_TO_CASE,
    SAR_FILED,
    DUPLICATE,
    INSUFFICIENT_EVIDENCE,
    RISK_ACCEPTED
}
