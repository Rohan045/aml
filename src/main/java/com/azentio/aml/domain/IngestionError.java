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
import org.hibernate.annotations.Immutable;

/**
 * A rejected input record together with the reason it failed validation. Malformed rows are
 * quarantined here instead of aborting the batch, giving operations a rework queue.
 */
@Entity
@Immutable
@Table(
        name = "ingestion_errors",
        indexes = {
            @Index(name = "idx_ingestion_error_batch", columnList = "batch_id"),
            @Index(name = "idx_ingestion_error_code", columnList = "error_code")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(onlyExplicitlyIncluded = true)
public class IngestionError implements java.io.Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "batch_id",
            nullable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_ingestion_error_batch"))
    private IngestionBatch batch;

    /** 1-based line or element number within the source payload. */
    @Column(name = "record_number", updatable = false)
    @ToString.Include
    private Integer recordNumber;

    /** Business key of the offending record when it could be parsed, e.g. {@code CUST_00001}. */
    @Size(max = 64)
    @Column(name = "record_identifier", length = 64, updatable = false)
    @ToString.Include
    private String recordIdentifier;

    @Size(max = 100)
    @Column(name = "field_name", length = 100, updatable = false)
    private String fieldName;

    @NotBlank
    @Size(max = 64)
    @Column(name = "error_code", length = 64, nullable = false, updatable = false)
    @ToString.Include
    private String errorCode;

    @NotBlank
    @Size(max = 1000)
    @Column(name = "error_message", length = 1000, nullable = false, updatable = false)
    private String errorMessage;

    /** Raw source row retained verbatim for reprocessing after correction. */
    @Column(name = "raw_record", columnDefinition = "text", updatable = false)
    private String rawRecord;

    @NotNull
    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof IngestionError that)) {
            return false;
        }
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
