package com.azentio.aml.domain;

import com.azentio.aml.domain.enums.IngestionEntityType;
import com.azentio.aml.domain.enums.IngestionSource;
import com.azentio.aml.domain.enums.IngestionStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
 * One ingestion run - a CSV upload, a REST batch or a window of the streaming feed - with its
 * record counts and outcome, so operators can prove what was loaded and what was rejected.
 */
@Entity
@Table(
        name = "ingestion_batches",
        uniqueConstraints =
                @UniqueConstraint(name = "uk_batch_reference", columnNames = "batch_reference"),
        indexes = {
            @Index(name = "idx_batch_status", columnList = "status"),
            @Index(name = "idx_batch_entity_type", columnList = "entity_type"),
            @Index(name = "idx_batch_started_at", columnList = "started_at")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(onlyExplicitlyIncluded = true)
public class IngestionBatch extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @NotBlank
    @Size(max = 64)
    @Column(name = "batch_reference", length = 64, nullable = false, updatable = false)
    @ToString.Include
    private String batchReference;

    @NotNull
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "source", length = 32, nullable = false)
    private IngestionSource source;

    @NotNull
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "entity_type", length = 32, nullable = false)
    @ToString.Include
    private IngestionEntityType entityType;

    @Size(max = 255)
    @Column(name = "file_name", length = 255)
    private String fileName;

    @Size(max = 64)
    @Column(name = "file_checksum", length = 64)
    private String fileChecksum;

    @NotNull
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", length = 32, nullable = false)
    @Builder.Default
    @ToString.Include
    private IngestionStatus status = IngestionStatus.RECEIVED;

    @PositiveOrZero
    @Column(name = "total_records")
    @Builder.Default
    private Integer totalRecords = 0;

    @PositiveOrZero
    @Column(name = "success_count")
    @Builder.Default
    private Integer successCount = 0;

    @PositiveOrZero
    @Column(name = "failure_count")
    @Builder.Default
    private Integer failureCount = 0;

    /** Records skipped because they had already been ingested (idempotent replay). */
    @PositiveOrZero
    @Column(name = "duplicate_count")
    @Builder.Default
    private Integer duplicateCount = 0;

    @NotNull
    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Size(max = 100)
    @Column(name = "submitted_by", length = 100)
    private String submittedBy;

    @Column(name = "error_summary", columnDefinition = "text")
    private String errorSummary;

    @OneToMany(
            mappedBy = "batch",
            cascade = CascadeType.ALL,
            orphanRemoval = true,
            fetch = FetchType.LAZY)
    @Builder.Default
    private List<IngestionError> errors = new ArrayList<>();

    public void addError(IngestionError error) {
        errors.add(error);
        error.setBatch(this);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof IngestionBatch that)) {
            return false;
        }
        return batchReference != null && batchReference.equals(that.batchReference);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(batchReference);
    }
}
