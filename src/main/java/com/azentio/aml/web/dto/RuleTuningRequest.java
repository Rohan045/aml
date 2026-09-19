package com.azentio.aml.web.dto;

import com.azentio.aml.domain.enums.AlertSeverity;
import com.azentio.aml.service.RuleConfigService;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;

/**
 * A partial tuning change to a detection rule.
 *
 * <p>Every field is optional and only the ones supplied are applied, so compliance can move a
 * single threshold without restating the whole rule - and cannot accidentally reset the other
 * twelve settings to null by omitting them.
 *
 * <p>The bounds here are the cheap, obviously-wrong checks; the cross-field rules (a minimum that
 * exceeds its maximum, for instance) live in the service, where they cannot be bypassed by a
 * caller that reaches the domain another way.
 */
@Schema(name = "RuleTuningRequest", description = "Partial tuning of a detection rule")
public record RuleTuningRequest(
        @Schema(description = "Switches the rule on or off for the next evaluation")
                Boolean enabled,
        @DecimalMin(value = "0.0", inclusive = false) BigDecimal thresholdAmount,
        @DecimalMin(value = "0.0", inclusive = false) BigDecimal thresholdAmountMin,
        @Min(1) Integer minOccurrences,
        @Min(1) @Max(8760) Integer timeWindowHours,
        @DecimalMin(value = "1.0") BigDecimal deviationMultiplier,
        @Min(1) @Max(3650) Integer lookbackDays,
        @DecimalMin(value = "0.0", inclusive = false) @DecimalMax(value = "1.0")
                BigDecimal ratioThreshold,
        @Min(1) @Max(8760) Integer dedupeWindowHours,
        @PositiveOrZero @Max(100) Integer riskWeight,
        AlertSeverity severity,
        @PositiveOrZero Integer executionOrder,
        @Schema(description = "Rule-specific extra parameters, as JSON") String parameters) {

    public RuleConfigService.RuleTuning toTuning() {
        return new RuleConfigService.RuleTuning(
                enabled,
                thresholdAmount,
                thresholdAmountMin,
                minOccurrences,
                timeWindowHours,
                deviationMultiplier,
                lookbackDays,
                ratioThreshold,
                dedupeWindowHours,
                riskWeight,
                severity,
                executionOrder,
                parameters);
    }
}
