package com.azentio.aml.web.dto;

import com.azentio.aml.domain.WatchlistEntry;
import com.azentio.aml.domain.enums.WatchlistSubjectType;
import com.azentio.aml.domain.enums.WatchlistType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * A sanctions / FATF / internal watchlist entry.
 *
 * <p>{@code normalizedValue} is shown because matching happens on it, not on the display value: an
 * entry that does not appear to match a transaction is almost always a normalisation difference,
 * and hiding the compared form would make that impossible to diagnose.
 */
@Schema(name = "WatchlistEntryView", description = "A watchlist or sanctions list entry")
public record WatchlistEntryView(
        Long id,
        WatchlistSubjectType subjectType,
        WatchlistType listType,
        String entryValue,
        @Schema(description = "Upper-cased, punctuation-collapsed form the matcher compares against")
                String normalizedValue,
        String displayName,
        Integer riskWeight,
        String source,
        Instant effectiveFrom,
        Instant effectiveTo,
        String notes,
        boolean activeNow) {

    public static WatchlistEntryView from(WatchlistEntry entry) {
        return new WatchlistEntryView(
                entry.getId(),
                entry.getSubjectType(),
                entry.getListType(),
                entry.getEntryValue(),
                entry.getNormalizedValue(),
                entry.getDisplayName(),
                entry.getRiskWeight(),
                entry.getSource(),
                entry.getEffectiveFrom(),
                entry.getEffectiveTo(),
                entry.getNotes(),
                entry.isActiveAt(Instant.now()));
    }
}
