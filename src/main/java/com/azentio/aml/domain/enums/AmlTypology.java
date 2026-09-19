package com.azentio.aml.domain.enums;

/**
 * Money laundering typologies detected by Sentinel. Each maps to one or more configurable rules in
 * {@code rule_configs} and drives the human-readable alert explanation.
 */
public enum AmlTypology {

    THRESHOLD_BREACH("CTR-style single transaction at or above the reporting threshold"),
    STRUCTURING("Multiple transactions deliberately kept just below the reporting threshold"),
    RAPID_MOVEMENT("Funds deposited and moved out again within a short window (layering)"),
    HIGH_RISK_JURISDICTION("Transfer to or from a sanctioned or high-risk jurisdiction"),
    BEHAVIOURAL_DEVIATION("Activity materially deviating from the customer's historical baseline"),
    ROUND_AMOUNT_PATTERN("Repeated suspiciously round or just-below-threshold amounts"),
    HIGH_RISK_COUNTERPARTY("Counterparty appears on a sanctions or internal watchlist"),
    DORMANT_ACCOUNT_REACTIVATION("Sudden high-value activity on a dormant account"),
    ANOMALY_SCORE("Statistical anomaly flagged by the complementary scoring model");

    private final String description;

    AmlTypology(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
