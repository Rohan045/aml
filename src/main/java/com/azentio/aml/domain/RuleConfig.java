package com.azentio.aml.domain;

import com.azentio.aml.domain.enums.AlertSeverity;
import com.azentio.aml.domain.enums.AmlTypology;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Runtime configuration of a single detection rule. Compliance can enable, disable and re-tune rules
 * through the admin API without a code change or redeployment.
 *
 * <p>The typed columns cover the thresholds the mandated rules need (amount, occurrence count, time
 * window, deviation multiplier); {@code parameters} carries any rule-specific extras as JSON.
 * {@code version} is incremented on every tuning change so alerts can record which configuration
 * produced them, enabling A/B comparison of threshold changes.
 */
@Entity
@Table(
        name = "rule_configs",
        uniqueConstraints = @UniqueConstraint(name = "uk_rule_code", columnNames = "rule_code"),
        indexes = {
            @Index(name = "idx_rule_enabled", columnList = "enabled"),
            @Index(name = "idx_rule_typology", columnList = "typology")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(onlyExplicitlyIncluded = true)
public class RuleConfig extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** Stable identifier referenced by alerts, e.g. {@code RULE_STRUCTURING_24H}. */
    @NotBlank
    @Size(max = 64)
    @Column(name = "rule_code", length = 64, nullable = false, updatable = false)
    @ToString.Include
    private String ruleCode;

    @NotBlank
    @Size(max = 160)
    @Column(name = "rule_name", length = 160, nullable = false)
    private String ruleName;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @NotNull
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "typology", length = 40, nullable = false)
    private AmlTypology typology;

    @NotNull
    @Column(name = "enabled", nullable = false)
    @Builder.Default
    @ToString.Include
    private Boolean enabled = Boolean.TRUE;

    /** Lower values run first; lets cheap rules short-circuit before expensive aggregations. */
    @NotNull
    @Column(name = "execution_order", nullable = false)
    @Builder.Default
    private Integer executionOrder = 100;

    @NotNull
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "severity", length = 20, nullable = false)
    @Builder.Default
    private AlertSeverity severity = AlertSeverity.MEDIUM;

    /** Contribution of this rule to the composite 0-100 alert risk score. */
    @NotNull
    @Min(0)
    @Column(name = "risk_weight", nullable = false)
    @Builder.Default
    private Integer riskWeight = 10;

    /** Upper/lower amount threshold in {@link #thresholdCurrency}, e.g. 10,000 for the CTR rule. */
    @Column(name = "threshold_amount", precision = 19, scale = 2)
    private BigDecimal thresholdAmount;

    /** Secondary bound, e.g. the 9,000 floor of the 9,000-9,999 structuring band. */
    @Column(name = "threshold_amount_min", precision = 19, scale = 2)
    private BigDecimal thresholdAmountMin;

    @Size(min = 3, max = 3)
    @Column(name = "threshold_currency", length = 3)
    @Builder.Default
    private String thresholdCurrency = "USD";

    /** Minimum number of qualifying events before the rule fires (e.g. 3 structuring legs). */
    @Positive
    @Column(name = "min_occurrences")
    private Integer minOccurrences;

    /** Sliding evaluation window in hours (e.g. 24 for structuring, 48 for rapid movement). */
    @Positive
    @Column(name = "time_window_hours")
    private Integer timeWindowHours;

    /** Baseline multiplier for deviation rules (e.g. 3.0 = 3x the 90-day rolling average). */
    @Column(name = "deviation_multiplier", precision = 9, scale = 2)
    private BigDecimal deviationMultiplier;

    /** Historical baseline length in days (e.g. 90). */
    @Positive
    @Column(name = "lookback_days")
    private Integer lookbackDays;

    /** Proportion trigger for rules such as "80% of an inflow moved out". */
    @Column(name = "ratio_threshold", precision = 5, scale = 4)
    private BigDecimal ratioThreshold;

    /** Window used to suppress duplicate alerts for the same pattern, in hours. */
    @Positive
    @Column(name = "dedupe_window_hours")
    @Builder.Default
    private Integer dedupeWindowHours = 24;

    /** Additional rule-specific settings serialised as JSON. */
    @Column(name = "parameters", columnDefinition = "text")
    private String parameters;

    /** Incremented on every tuning change; stamped onto alerts for rule-version traceability. */
    @NotNull
    @Min(1)
    @Column(name = "config_version", nullable = false)
    @Builder.Default
    private Integer configVersion = 1;

    @Column(name = "effective_from")
    private Instant effectiveFrom;

    @Column(name = "effective_to")
    private Instant effectiveTo;

    @Size(max = 100)
    @Column(name = "last_tuned_by", length = 100)
    private String lastTunedBy;

    @Column(name = "last_tuned_at")
    private Instant lastTunedAt;

    public boolean isActiveAt(Instant moment) {
        return Boolean.TRUE.equals(enabled)
                && (effectiveFrom == null || !moment.isBefore(effectiveFrom))
                && (effectiveTo == null || moment.isBefore(effectiveTo));
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RuleConfig that)) {
            return false;
        }
        return ruleCode != null && ruleCode.equals(that.ruleCode);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(ruleCode);
    }
}
