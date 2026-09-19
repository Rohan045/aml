package com.azentio.aml.repository;

import com.azentio.aml.domain.WatchlistEntry;
import com.azentio.aml.domain.enums.WatchlistSubjectType;
import com.azentio.aml.domain.enums.WatchlistType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Sanctions, FATF and internal high-risk lists.
 *
 * <p>Screening loads the relevant values once per sweep via {@link #findActiveValues} and matches
 * in memory against an upper-cased set, rather than issuing a lookup per transaction. The lists are
 * small and change rarely, so this turns a per-row database call into a hash lookup.
 */
public interface WatchlistEntryRepository extends JpaRepository<WatchlistEntry, Long> {

    Optional<WatchlistEntry> findBySubjectTypeAndListTypeAndNormalizedValue(
            WatchlistSubjectType subjectType, WatchlistType listType, String normalizedValue);

    /** Normalised values only - the minimal payload needed to build the screening set. */
    @Query(
            """
            select w.normalizedValue
              from WatchlistEntry w
             where w.active = true
               and w.subjectType = :subjectType
               and w.listType in :listTypes
               and (w.effectiveFrom is null or w.effectiveFrom <= :at)
               and (w.effectiveTo   is null or w.effectiveTo   >  :at)
            """)
    List<String> findActiveValues(
            @Param("subjectType") WatchlistSubjectType subjectType,
            @Param("listTypes") Collection<WatchlistType> listTypes,
            @Param("at") Instant at);

    /** Full entries, needed when an alert must explain which list was hit and at what weight. */
    @Query(
            """
            select w
              from WatchlistEntry w
             where w.active = true
               and w.subjectType = :subjectType
               and w.listType in :listTypes
               and (w.effectiveFrom is null or w.effectiveFrom <= :at)
               and (w.effectiveTo   is null or w.effectiveTo   >  :at)
             order by w.riskWeight desc
            """)
    List<WatchlistEntry> findActiveEntries(
            @Param("subjectType") WatchlistSubjectType subjectType,
            @Param("listTypes") Collection<WatchlistType> listTypes,
            @Param("at") Instant at);

    /** Resolves a single hit back to its entry so the alert can name the list and source. */
    @Query(
            """
            select w
              from WatchlistEntry w
             where w.active = true
               and w.subjectType = :subjectType
               and w.normalizedValue = :normalizedValue
               and (w.effectiveFrom is null or w.effectiveFrom <= :at)
               and (w.effectiveTo   is null or w.effectiveTo   >  :at)
             order by w.riskWeight desc
            """)
    List<WatchlistEntry> findMatches(
            @Param("subjectType") WatchlistSubjectType subjectType,
            @Param("normalizedValue") String normalizedValue,
            @Param("at") Instant at);
}
