package com.azentio.aml.domain;

import com.azentio.aml.domain.enums.CasePriority;
import com.azentio.aml.domain.enums.CaseStatus;
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
 * Investigation container grouping one or more {@link Alert}s for a customer, giving analysts the
 * workflow the CCO asked for: assign, investigate, escalate, file a SAR, close with a reason.
 *
 * <p>Named {@code AmlCase} because {@code case} is a reserved word in both Java and SQL.
 */
@Entity
@Table(
        name = "aml_cases",
        uniqueConstraints = @UniqueConstraint(name = "uk_case_number", columnNames = "case_number"),
        indexes = {
            @Index(name = "idx_case_status_priority", columnList = "status,priority"),
            @Index(name = "idx_case_customer", columnList = "customer_id"),
            @Index(name = "idx_case_assigned_to", columnList = "assigned_to"),
            @Index(name = "idx_case_opened_at", columnList = "opened_at")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(onlyExplicitlyIncluded = true)
public class AmlCase extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** Human-facing reference, e.g. {@code CASE-2026-000045}. */
    @NotBlank
    @Size(max = 32)
    @Column(name = "case_number", length = 32, nullable = false, updatable = false)
    @ToString.Include
    private String caseNumber;

    @NotBlank
    @Size(max = 200)
    @Column(name = "title", length = 200, nullable = false)
    private String title;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "customer_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_case_customer"))
    private Customer customer;

    @NotNull
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", length = 20, nullable = false)
    @Builder.Default
    private CaseStatus status = CaseStatus.OPEN;

    @NotNull
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "priority", length = 20, nullable = false)
    @Builder.Default
    private CasePriority priority = CasePriority.MEDIUM;

    /** Highest risk score across the linked alerts; drives queue ordering. */
    @Min(0)
    @Max(100)
    @Column(name = "aggregate_risk_score")
    @ToString.Include
    private Integer aggregateRiskScore;

    @Column(name = "total_exposure_amount", precision = 19, scale = 2)
    private BigDecimal totalExposureAmount;

    @Size(max = 100)
    @Column(name = "assigned_to", length = 100)
    private String assignedTo;

    @Column(name = "assigned_at")
    private Instant assignedAt;

    @Size(max = 100)
    @Column(name = "opened_by", length = 100)
    private String openedBy;

    @NotNull
    @Column(name = "opened_at", nullable = false)
    private Instant openedAt;

    /** SLA deadline for disposition; powers the time-to-disposition metric. */
    @Column(name = "due_at")
    private Instant dueAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Size(max = 100)
    @Column(name = "closed_by", length = 100)
    private String closedBy;

    @Column(name = "closure_reason", columnDefinition = "text")
    private String closureReason;

    /** Draft SAR narrative summarising the evidence for regulatory filing. */
    @Column(name = "narrative", columnDefinition = "text")
    private String narrative;

    @NotNull
    @Column(name = "sar_filed", nullable = false)
    @Builder.Default
    private Boolean sarFiled = Boolean.FALSE;

    @Size(max = 64)
    @Column(name = "sar_reference", length = 64)
    private String sarReference;

    @Column(name = "sar_filed_at")
    private Instant sarFiledAt;

    @OneToMany(mappedBy = "amlCase", fetch = FetchType.LAZY)
    @Builder.Default
    private Set<Alert> alerts = new LinkedHashSet<>();

    @OneToMany(
            mappedBy = "amlCase",
            cascade = CascadeType.ALL,
            orphanRemoval = true,
            fetch = FetchType.LAZY)
    @Builder.Default
    private List<CaseNote> notes = new ArrayList<>();

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public void linkAlert(Alert alert) {
        alerts.add(alert);
        alert.setAmlCase(this);
    }

    public void addNote(CaseNote note) {
        notes.add(note);
        note.setAmlCase(this);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AmlCase that)) {
            return false;
        }
        return caseNumber != null && caseNumber.equals(that.caseNumber);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(caseNumber);
    }
}
