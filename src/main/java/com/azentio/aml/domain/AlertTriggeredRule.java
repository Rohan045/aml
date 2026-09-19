package com.azentio.aml.domain;

import com.azentio.aml.domain.enums.AmlTypology;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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
 * One rule firing that contributed to an {@link Alert}. An alert may aggregate several rules; the
 * per-rule {@code contributedScore} makes the composite risk score fully explainable and auditable,
 * and {@code ruleVersion} records which tuning of the rule was in force at detection time.
 */
@Entity
@Table(
        name = "alert_triggered_rules",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_alert_rule",
                        columnNames = {"alert_id", "rule_code"}),
        indexes = {
            @Index(name = "idx_triggered_rule_alert", columnList = "alert_id"),
            @Index(name = "idx_triggered_rule_code", columnList = "rule_code")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(onlyExplicitlyIncluded = true)
public class AlertTriggeredRule extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "alert_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_triggered_rule_alert"))
    private Alert alert;

    @NotBlank
    @Size(max = 64)
    @Column(name = "rule_code", length = 64, nullable = false)
    @ToString.Include
    private String ruleCode;

    @Size(max = 160)
    @Column(name = "rule_name", length = 160)
    private String ruleName;

    /** Version of the rule configuration in force when this rule fired. */
    @Column(name = "rule_version")
    private Integer ruleVersion;

    @NotNull
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "typology", length = 40, nullable = false)
    private AmlTypology typology;

    /** Configured weight of the rule at detection time. */
    @Column(name = "rule_weight")
    private Integer ruleWeight;

    /** Points this rule added to the alert's composite risk score. */
    @Column(name = "contributed_score")
    @ToString.Include
    private Integer contributedScore;

    /** Snapshot of thresholds and observed values, serialised as JSON, for the evidence pack. */
    @Column(name = "details", columnDefinition = "text")
    private String details;

    @NotNull
    @Column(name = "triggered_at", nullable = false)
    private Instant triggeredAt;

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AlertTriggeredRule that)) {
            return false;
        }
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
