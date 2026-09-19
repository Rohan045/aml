package com.azentio.aml.domain;

import com.azentio.aml.domain.enums.WatchlistSubjectType;
import com.azentio.aml.domain.enums.WatchlistType;
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
 * Configurable high-risk / sanctions list entry. Covers both jurisdictions (subject type
 * {@code COUNTRY}, value = ISO alpha-2 code) and named counterparties, so a single screening
 * component can serve the high-risk jurisdiction and high-risk counterparty rules.
 *
 * <p>{@code normalizedValue} is the uppercased, punctuation-stripped form used for matching.
 */
@Entity
@Table(
        name = "watchlist_entries",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_watchlist_subject_value",
                        columnNames = {"subject_type", "list_type", "normalized_value"}),
        indexes = {
            @Index(name = "idx_watchlist_lookup", columnList = "subject_type,normalized_value,active"),
            @Index(name = "idx_watchlist_list_type", columnList = "list_type")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(onlyExplicitlyIncluded = true)
public class WatchlistEntry extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @NotNull
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "subject_type", length = 32, nullable = false)
    @ToString.Include
    private WatchlistSubjectType subjectType;

    @NotNull
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "list_type", length = 32, nullable = false)
    @ToString.Include
    private WatchlistType listType;

    /** Raw value as published, e.g. {@code IR} or {@code "Northern Shell Holdings Ltd"}. */
    @NotBlank
    @Size(max = 200)
    @Column(name = "entry_value", length = 200, nullable = false)
    @ToString.Include
    private String entryValue;

    /** Uppercased and punctuation-stripped form of {@link #entryValue}, used for matching. */
    @NotBlank
    @Size(max = 200)
    @Column(name = "normalized_value", length = 200, nullable = false)
    private String normalizedValue;

    @Size(max = 200)
    @Column(name = "display_name", length = 200)
    private String displayName;

    /** Points contributed to the alert risk score when this entry matches. */
    @NotNull
    @Min(0)
    @Column(name = "risk_weight", nullable = false)
    @Builder.Default
    private Integer riskWeight = 25;

    @Size(max = 120)
    @Column(name = "source", length = 120)
    private String source;

    @NotNull
    @Column(name = "active", nullable = false)
    @Builder.Default
    private Boolean active = Boolean.TRUE;

    @Column(name = "effective_from")
    private Instant effectiveFrom;

    @Column(name = "effective_to")
    private Instant effectiveTo;

    @Column(name = "notes", columnDefinition = "text")
    private String notes;

    public boolean isActiveAt(Instant moment) {
        return Boolean.TRUE.equals(active)
                && (effectiveFrom == null || !moment.isBefore(effectiveFrom))
                && (effectiveTo == null || moment.isBefore(effectiveTo));
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof WatchlistEntry that)) {
            return false;
        }
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
