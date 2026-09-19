package com.azentio.aml.web.dto;

import com.azentio.aml.domain.AlertTriggeredRule;
import com.azentio.aml.domain.enums.AmlTypology;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * One rule that contributed to an alert.
 *
 * <p>{@code ruleVersion} and {@code contributedScore} together let compliance answer the question a
 * threshold change always raises: which configuration produced this alert, and how much of the
 * score came from this particular rule.
 */
@Schema(name = "TriggeredRuleView", description = "A detection rule that fired on this alert")
public record TriggeredRuleView(
        @Schema(example = "STRUCTURING_24H") String ruleCode,
        String ruleName,
        AmlTypology typology,
        @Schema(description = "Rule configuration version in force when the alert was raised")
                Integer ruleVersion,
        Integer ruleWeight,
        @Schema(description = "Points this rule contributed to the composite risk score")
                Integer contributedScore,
        @Schema(description = "Rule-specific thresholds and observed values, as JSON")
                String details,
        Instant triggeredAt) {

    public static TriggeredRuleView from(AlertTriggeredRule rule) {
        return new TriggeredRuleView(
                rule.getRuleCode(),
                rule.getRuleName(),
                rule.getTypology(),
                rule.getRuleVersion(),
                rule.getRuleWeight(),
                rule.getContributedScore(),
                rule.getDetails(),
                rule.getTriggeredAt());
    }
}
