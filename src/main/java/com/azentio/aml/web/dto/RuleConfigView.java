package com.azentio.aml.web.dto;

import com.azentio.aml.domain.RuleConfig;
import com.azentio.aml.domain.enums.AlertSeverity;
import com.azentio.aml.domain.enums.AmlTypology;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * A detection rule's current configuration.
 *
 * <p>This is the "rule configuration without redeployment" surface: every threshold, window and
 * weight the engine reads at evaluation time is visible here and changeable through the tuning
 * endpoint. {@code configVersion}, {@code lastTunedBy} and {@code lastTunedAt} make the change
 * history of a rule self-evident, which is what allows the effect of a tuning change on alert
 * volume to be measured afterwards.
 */
@Schema(name = "RuleConfigView", description = "Runtime configuration of a detection rule")
public record RuleConfigView(
        @Schema(example = "STRUCTURING_24H") String ruleCode,
        String ruleName,
        String description,
        AmlTypology typology,
        boolean enabled,
        @Schema(description = "Upper threshold, in the platform base currency")
                BigDecimal thresholdAmount,
        @Schema(description = "Lower bound of a banded threshold, e.g. the 9,000 in 9,000-9,999")
                BigDecimal thresholdAmountMin,
        Integer minOccurrences,
        Integer timeWindowHours,
        BigDecimal deviationMultiplier,
        Integer lookbackDays,
        BigDecimal ratioThreshold,
        Integer dedupeWindowHours,
        @Schema(description = "Contribution of this rule to the composite risk score")
                Integer riskWeight,
        AlertSeverity severity,
        Integer executionOrder,
        @Schema(description = "Rule-specific extra parameters, as JSON") String parameters,
        Integer configVersion,
        Instant effectiveFrom,
        Instant effectiveTo,
        String lastTunedBy,
        Instant lastTunedAt,
        @Schema(description = "False when no rule bean implements this configured code")
                boolean implemented) {

    public static RuleConfigView from(RuleConfig config, boolean implemented) {
        return new RuleConfigView(
                config.getRuleCode(),
                config.getRuleName(),
                config.getDescription(),
                config.getTypology(),
                Boolean.TRUE.equals(config.getEnabled()),
                config.getThresholdAmount(),
                config.getThresholdAmountMin(),
                config.getMinOccurrences(),
                config.getTimeWindowHours(),
                config.getDeviationMultiplier(),
                config.getLookbackDays(),
                config.getRatioThreshold(),
                config.getDedupeWindowHours(),
                config.getRiskWeight(),
                config.getSeverity(),
                config.getExecutionOrder(),
                config.getParameters(),
                config.getConfigVersion(),
                config.getEffectiveFrom(),
                config.getEffectiveTo(),
                config.getLastTunedBy(),
                config.getLastTunedAt(),
                implemented);
    }
}
