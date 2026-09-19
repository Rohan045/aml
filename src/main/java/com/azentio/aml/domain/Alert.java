package com.azentio.aml.domain;

import com.azentio.aml.domain.enums.AlertDisposition;
import com.azentio.aml.domain.enums.AlertSeverity;
import com.azentio.aml.domain.enums.AlertStatus;
import com.azentio.aml.domain.enums.AmlTypology;
import jakarta.persistence.CascadeType;
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
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A risk-scored suspicion raised by the detection engine.
 *
 * <p>Alerts are append-only from a business perspective: they are never deleted, only closed with a
 * {@link AlertDisposition} plus the analyst identity, so the audit trail stays intact.
 *
 * <p>{@code dedupeKey} is unique and carries the customer, typology and detection window bucket. It
 * is the database-level guard that stops concurrent detection threads raising fifty redundant
 * alerts for the same underlying pattern - a repeat detection increments {@link #occurrenceCount}
 * instead.
 */
@Entity
@Table(
        name = "alerts",
        uniqueConstraints = {
            @UniqueConstraint(name = "uk_alert_reference", columnNames = "alert_reference"),
            @UniqueConstraint(name = "uk_alert_dedupe_key", columnNames = "dedupe_key")
        },
        indexes = {
            @Index(name = "idx_alert_status_score", columnList = "status,risk_score"),
            @Index(name = "idx_alert_customer", columnList = "customer_id"),
            @Index(name = "idx_alert_account", columnList = "account_id"),
            @Index(name = "idx_alert_typology", columnList = "typology"),
            @Index(name = "idx_alert_severity", columnList = "severity"),
            @Index(name = "idx_alert_case", columnList = "case_id"),
            @Index(name = "idx_alert_detected_at", columnList = "first_detected_at")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(onlyExplicitlyIncluded = true)
public class Alert extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** Human-facing reference shown to analysts, e.g. {@code ALT-2026-000123}. */
    @NotBlank
    @Size(max = 32)
    @Column(name = "alert_reference", length = 32, nullable = false, updatable = false)
    @ToString.Include
    private String alertReference;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "customer_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_alert_customer"))
    private Customer customer;

    /** Null for customer-level typologies that span several accounts. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", foreignKey = @ForeignKey(name = "fk_alert_account"))
    private Account account;

    @NotNull
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "typology", length = 40, nullable = false)
    @ToString.Include
    private AmlTypology typology;

    @NotNull
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", length = 20, nullable = false)
    @Builder.Default
    private AlertStatus status = AlertStatus.NEW;

    @NotNull
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "severity", length = 20, nullable = false)
    @Builder.Default
    private AlertSeverity severity = AlertSeverity.LOW;

    /** Weighted 0-100 score used to sort the analyst queue. */
    @NotNull
    @Min(0)
    @Max(100)
    @Column(name = "risk_score", nullable = false)
    @ToString.Include
    @Builder.Default
    private Integer riskScore = 0;

    @NotBlank
    @Size(max = 200)
    @Column(name = "title", length = 200, nullable = false)
    private String title;

    /** Plain-English reason the pattern was flagged - the "explain WHY" requirement. */
    @NotBlank
    @Column(name = "explanation", columnDefinition = "text", nullable = false)
    private String explanation;

    /** Deterministic de-duplication key: customer + typology + detection window bucket. */
    @NotBlank
    @Size(max = 255)
    @Column(name = "dedupe_key", length = 255, nullable = false)
    private String dedupeKey;

    /** Number of times this same pattern re-fired while the alert was open. */
    @NotNull
    @Min(1)
    @Column(name = "occurrence_count", nullable = false)
    @Builder.Default
    private Integer occurrenceCount = 1;

    @Column(name = "detection_window_start")
    private Instant detectionWindowStart;

    @Column(name = "detection_window_end")
    private Instant detectionWindowEnd;

    @NotNull
    @Column(name = "first_detected_at", nullable = false)
    private Instant firstDetectedAt;

    @NotNull
    @Column(name = "last_detected_at", nullable = false)
    private Instant lastDetectedAt;

    /** Aggregate value of the evidence transactions, in base currency. */
    @Column(name = "total_amount", precision = 19, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "transaction_count")
    private Integer transactionCount;

    @Size(max = 100)
    @Column(name = "assigned_to", length = 100)
    private String assignedTo;

    @Column(name = "assigned_at")
    private Instant assignedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "case_id", foreignKey = @ForeignKey(name = "fk_alert_case"))
    private AmlCase amlCase;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "disposition", length = 32)
    private AlertDisposition disposition;

    @Column(name = "disposition_reason", columnDefinition = "text")
    private String dispositionReason;

    /** Analyst identity retained for audit even after closure. */
    @Size(max = 100)
    @Column(name = "disposition_by", length = 100)
    private String dispositionBy;

    @Column(name = "disposition_at")
    private Instant dispositionAt;

    @OneToMany(
            mappedBy = "alert",
            cascade = CascadeType.ALL,
            orphanRemoval = true,
            fetch = FetchType.LAZY)
    @Builder.Default
    private Set<AlertTriggeredRule> triggeredRules = new LinkedHashSet<>();

    @OneToMany(
            mappedBy = "alert",
            cascade = CascadeType.ALL,
            orphanRemoval = true,
            fetch = FetchType.LAZY)
    @Builder.Default
    private List<AlertEvidence> evidence = new ArrayList<>();

    /** Guards against lost updates when two analysts or detection threads touch the same alert. */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public void addTriggeredRule(AlertTriggeredRule rule) {
        triggeredRules.add(rule);
        rule.setAlert(this);
    }

    public void addEvidence(AlertEvidence item) {
        evidence.add(item);
        item.setAlert(this);
    }

    /** Keeps the severity band consistent with the score. */
    public void applyRiskScore(int score) {
        this.riskScore = Math.max(0, Math.min(100, score));
        this.severity = AlertSeverity.fromScore(this.riskScore);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Alert that)) {
            return false;
        }
        return alertReference != null && alertReference.equals(that.alertReference);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(alertReference);
    }
}
