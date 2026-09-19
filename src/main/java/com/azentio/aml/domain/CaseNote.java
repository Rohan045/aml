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
 * Append-only investigation note on a case. Notes are immutable once written so the investigation
 * narrative cannot be rewritten after the fact.
 */
@Entity
@Immutable
@Table(
        name = "case_notes",
        indexes = {
            @Index(name = "idx_case_note_case", columnList = "case_id"),
            @Index(name = "idx_case_note_created", columnList = "note_created_at")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(onlyExplicitlyIncluded = true)
public class CaseNote extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "case_id",
            nullable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_case_note_case"))
    private AmlCase amlCase;

    @NotBlank
    @Size(max = 100)
    @Column(name = "author", length = 100, nullable = false, updatable = false)
    @ToString.Include
    private String author;

    @NotBlank
    @Column(name = "note", columnDefinition = "text", nullable = false, updatable = false)
    private String note;

    @NotNull
    @Column(name = "note_created_at", nullable = false, updatable = false)
    private Instant noteCreatedAt;

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof CaseNote that)) {
            return false;
        }
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
