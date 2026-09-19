package com.azentio.aml.web.dto;

import com.azentio.aml.domain.WatchlistEntry;
import com.azentio.aml.domain.enums.WatchlistSubjectType;
import com.azentio.aml.domain.enums.WatchlistType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/**
 * Adds or replaces a watchlist entry.
 *
 * <p>Entries are effective-dated rather than deleted: a transaction screened last March must still
 * be explicable by the list as it stood in March, so removing a jurisdiction means closing its
 * validity period, not erasing the row.
 */
@Schema(name = "WatchlistEntryRequest", description = "A new or updated watchlist entry")
public record WatchlistEntryRequest(
        @NotNull WatchlistSubjectType subjectType,
        @NotNull WatchlistType listType,
        @NotBlank @Size(max = 200)
                @Schema(
                        example = "KP",
                        description =
                                "ISO alpha-2 code for a country, or the counterparty/bank name")
                String entryValue,
        @Size(max = 200) String displayName,
        @PositiveOrZero @Max(100)
                @Schema(description = "Contribution to the risk score when this entry matches")
                Integer riskWeight,
        @Size(max = 100) @Schema(example = "FATF") String source,
        @Schema(description = "Defaults to now when omitted") Instant effectiveFrom,
        @Schema(description = "Leave null for an open-ended entry") Instant effectiveTo,
        @Size(max = 2000) String notes) {

    /** {@code normalizedValue} is set by the service, which owns the matcher's normalisation. */
    public WatchlistEntry toEntry() {
        return WatchlistEntry.builder()
                .subjectType(subjectType)
                .listType(listType)
                .entryValue(entryValue)
                .displayName(displayName)
                .riskWeight(riskWeight == null ? 25 : riskWeight)
                .source(source)
                .effectiveFrom(effectiveFrom == null ? Instant.now() : effectiveFrom)
                .effectiveTo(effectiveTo)
                .notes(notes)
                .build();
    }
}
