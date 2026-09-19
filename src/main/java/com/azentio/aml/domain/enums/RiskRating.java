package com.azentio.aml.domain.enums;

/** Customer / account KYC risk classification. {@code weight} feeds the alert risk score. */
public enum RiskRating {
    LOW(5),
    MEDIUM(15),
    HIGH(30),
    CRITICAL(45);

    private final int weight;

    RiskRating(int weight) {
        this.weight = weight;
    }

    public int getWeight() {
        return weight;
    }
}
