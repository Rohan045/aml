package com.azentio.aml.domain.enums;

/** Severity banding derived from the 0-100 alert risk score. */
public enum AlertSeverity {
    LOW(0, 39),
    MEDIUM(40, 59),
    HIGH(60, 79),
    CRITICAL(80, 100);

    private final int minScore;
    private final int maxScore;

    AlertSeverity(int minScore, int maxScore) {
        this.minScore = minScore;
        this.maxScore = maxScore;
    }

    public int getMinScore() {
        return minScore;
    }

    public int getMaxScore() {
        return maxScore;
    }

    public static AlertSeverity fromScore(int score) {
        for (AlertSeverity severity : values()) {
            if (score >= severity.minScore && score <= severity.maxScore) {
                return severity;
            }
        }
        return score < 0 ? LOW : CRITICAL;
    }
}
