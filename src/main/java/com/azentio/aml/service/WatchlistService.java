package com.azentio.aml.service;

import com.azentio.aml.domain.WatchlistEntry;
import com.azentio.aml.domain.enums.WatchlistSubjectType;
import com.azentio.aml.domain.enums.WatchlistType;
import com.azentio.aml.repository.WatchlistEntryRepository;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sanctions / FATF / internal watchlist screening.
 *
 * <p>Screening happens against an in-memory snapshot rather than one query per transaction: the
 * lists hold a few thousand rows at most, so a hash lookup replaces a database round trip on the
 * hot path. A snapshot is taken once per detection sweep and used for every transaction in it,
 * which also makes the sweep's results internally consistent even if the list is edited mid-run.
 */
@Service
public class WatchlistService {

    private static final Logger log = LoggerFactory.getLogger(WatchlistService.class);

    /** Jurisdiction lists that trigger an alert outright (business rule 4). */
    public static final List<WatchlistType> BLOCKING_COUNTRY_LISTS =
            List.of(
                    WatchlistType.SANCTIONS,
                    WatchlistType.FATF_BLACKLIST,
                    WatchlistType.FATF_GREYLIST);

    /** Secrecy jurisdictions: a scoring uplift, not a trigger on their own. */
    public static final List<WatchlistType> UPLIFT_COUNTRY_LISTS = List.of(WatchlistType.TAX_HAVEN);

    /** Counterparty lists that trigger an alert outright. */
    public static final List<WatchlistType> BLOCKING_COUNTERPARTY_LISTS =
            List.of(
                    WatchlistType.SANCTIONS,
                    WatchlistType.ADVERSE_MEDIA,
                    WatchlistType.INTERNAL_HIGH_RISK);

    private final WatchlistEntryRepository watchlistEntryRepository;

    public WatchlistService(WatchlistEntryRepository watchlistEntryRepository) {
        this.watchlistEntryRepository = watchlistEntryRepository;
    }

    /** Point-in-time snapshot of every list the detection engine screens against. */
    @Transactional(readOnly = true)
    public Snapshot snapshot(Instant at) {
        Map<String, WatchlistEntry> countries = index(WatchlistSubjectType.COUNTRY, at);
        Map<String, WatchlistEntry> counterparties = new HashMap<>();
        counterparties.putAll(index(WatchlistSubjectType.COUNTERPARTY, at));
        counterparties.putAll(index(WatchlistSubjectType.BANK, at));
        counterparties.putAll(index(WatchlistSubjectType.ENTITY, at));
        counterparties.putAll(index(WatchlistSubjectType.INDIVIDUAL, at));
        counterparties.putAll(index(WatchlistSubjectType.ACCOUNT_NUMBER, at));
        log.debug(
                "Watchlist snapshot at {}: {} jurisdictions, {} counterparties",
                at,
                countries.size(),
                counterparties.size());
        return new Snapshot(countries, counterparties);
    }

    @Transactional(readOnly = true)
    public List<WatchlistEntry> findAll() {
        return watchlistEntryRepository.findAll();
    }

    @Transactional(readOnly = true)
    public List<String> activeCountryCodes(Collection<WatchlistType> listTypes, Instant at) {
        return watchlistEntryRepository.findActiveValues(
                WatchlistSubjectType.COUNTRY, listTypes, at);
    }

    @Transactional
    @CacheEvict(cacheNames = "watchlistValues", allEntries = true)
    public WatchlistEntry save(WatchlistEntry entry) {
        entry.setNormalizedValue(normalize(entry.getEntryValue()));
        return watchlistEntryRepository.save(entry);
    }

    /** Uppercased, punctuation-collapsed form both the list and the transaction are compared in. */
    public static String normalize(String value) {
        if (value == null) {
            return null;
        }
        return value.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", " ").trim();
    }

    private Map<String, WatchlistEntry> index(WatchlistSubjectType subjectType, Instant at) {
        List<WatchlistType> allListTypes = List.of(WatchlistType.values());
        Map<String, WatchlistEntry> byValue = new HashMap<>();
        for (WatchlistEntry entry :
                watchlistEntryRepository.findActiveEntries(subjectType, allListTypes, at)) {
            // findActiveEntries orders by risk weight descending, so the first entry for a value
            // wins and the match always reports the most severe list the subject appears on.
            byValue.putIfAbsent(normalize(entry.getNormalizedValue()), entry);
        }
        return byValue;
    }

    /**
     * Immutable screening snapshot.
     *
     * @param countries active country entries keyed by ISO alpha-2 code
     * @param counterparties active counterparty/bank/account entries keyed by normalised value
     */
    public record Snapshot(
            Map<String, WatchlistEntry> countries, Map<String, WatchlistEntry> counterparties) {

        /** A hit on one of the blocking jurisdiction lists, or empty. */
        public Optional<WatchlistEntry> matchCountry(String countryCode) {
            return match(countries, countryCode, BLOCKING_COUNTRY_LISTS);
        }

        /** A hit on a secrecy jurisdiction, which lifts the score without triggering on its own. */
        public Optional<WatchlistEntry> matchUpliftCountry(String countryCode) {
            return match(countries, countryCode, UPLIFT_COUNTRY_LISTS);
        }

        public Optional<WatchlistEntry> matchCounterparty(String value) {
            return match(counterparties, value, BLOCKING_COUNTERPARTY_LISTS);
        }

        /** The distinct normalised values of every blocking counterparty entry. */
        public Set<String> blockingCounterpartyValues() {
            Set<String> values = new LinkedHashSet<>();
            counterparties.forEach(
                    (value, entry) -> {
                        if (BLOCKING_COUNTERPARTY_LISTS.contains(entry.getListType())) {
                            values.add(value);
                        }
                    });
            return values;
        }

        public Set<String> blockingCountryCodes() {
            Set<String> values = new LinkedHashSet<>();
            countries.forEach(
                    (value, entry) -> {
                        if (BLOCKING_COUNTRY_LISTS.contains(entry.getListType())) {
                            values.add(value);
                        }
                    });
            return values;
        }

        private static Optional<WatchlistEntry> match(
                Map<String, WatchlistEntry> index,
                String value,
                Collection<WatchlistType> applicableLists) {
            if (value == null || value.isBlank()) {
                return Optional.empty();
            }
            WatchlistEntry entry = index.get(normalize(value));
            if (entry == null || !applicableLists.contains(entry.getListType())) {
                return Optional.empty();
            }
            return Optional.of(entry);
        }
    }
}
