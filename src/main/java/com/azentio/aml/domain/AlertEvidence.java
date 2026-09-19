package com.azentio.aml.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Link between an {@link Alert} and a {@link Transaction} that supports it. {@code externalTxnId},
 * {@code baseAmount} and {@code occurredAt} are snapshotted so the evidence pack stays readable even
 * if the transaction row is later archived.
 */
@Entity
@Table(
        name = "alert_evidence",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_alert_evidence_txn",
                        columnNames = {"alert_id", "transaction_id"}),
        indexes = {
            @Index(name = "idx_evidence_alert", columnList = "alert_id"),
            @Index(name = "idx_evidence_transaction", columnList = "transaction_id")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(onlyExplicitlyIncluded = true)
public class AlertEvidence extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "alert_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_evidence_alert"))
    private Alert alert;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "transaction_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_evidence_transaction"))
    private Transaction transaction;

    @NotBlank
    @Size(max = 64)
    @Column(name = "external_txn_id", length = 64, nullable = false)
    @ToString.Include
    private String externalTxnId;

    /** Part the transaction plays in the typology, e.g. {@code INFLOW}, {@code STRUCTURING_LEG}. */
    @Size(max = 40)
    @Column(name = "evidence_role", length = 40)
    private String evidenceRole;

    @Column(name = "base_amount", precision = 19, scale = 2)
    private BigDecimal baseAmount;

    @Column(name = "occurred_at")
    private Instant occurredAt;

    @Size(max = 500)
    @Column(name = "note", length = 500)
    private String note;

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AlertEvidence that)) {
            return false;
        }
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
