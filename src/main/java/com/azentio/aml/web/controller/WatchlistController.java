package com.azentio.aml.web.controller;

import com.azentio.aml.domain.WatchlistEntry;
import com.azentio.aml.service.WatchlistService;
import com.azentio.aml.web.dto.WatchlistEntryRequest;
import com.azentio.aml.web.dto.WatchlistEntryView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sanctions, FATF and internal watchlist maintenance.
 *
 * <p>Business rule 4 makes these lists a hard trigger: a transaction touching a listed jurisdiction
 * or counterparty alerts regardless of amount. Editing them therefore changes what the bank
 * detects, which is why the path is restricted to compliance and admin roles by the filter chain
 * and writes exclude the read-only auditor.
 *
 * <p>There is no delete endpoint, and that is deliberate. Entries are effective-dated: a
 * jurisdiction that comes off the list has its validity period closed, so an alert raised last
 * quarter remains explicable by the list as it stood at the time. Erasing the row would make the
 * historical decision unauditable.
 */
@RestController
@RequestMapping("/api/v1/watchlist")
@Validated
@Tag(name = "Watchlist", description = "Sanctions, FATF and internal high-risk lists")
public class WatchlistController {

    private final WatchlistService watchlistService;

    public WatchlistController(WatchlistService watchlistService) {
        this.watchlistService = watchlistService;
    }

    @GetMapping
    @Operation(summary = "Every watchlist entry, including expired ones")
    public List<WatchlistEntryView> list() {
        return watchlistService.findAll().stream().map(WatchlistEntryView::from).toList();
    }

    @GetMapping("/high-risk-countries")
    @Operation(
            summary = "Jurisdictions that trigger an alert outright",
            description =
                    "The sanctions and FATF call-for-action / increased-monitoring lists as they"
                            + " stand right now. Tax havens are excluded: they lift a score but do"
                            + " not trigger on their own.")
    public List<String> highRiskCountries() {
        return watchlistService.activeCountryCodes(
                WatchlistService.BLOCKING_COUNTRY_LISTS, Instant.now());
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('COMPLIANCE_OFFICER','ADMIN')")
    @Operation(
            summary = "Add or update a watchlist entry",
            description =
                    "The matcher's normalised form is derived by the service, so the entry will"
                            + " match regardless of the punctuation or casing supplied here. To"
                            + " retire an entry, re-post it with an 'effectiveTo' in the past.")
    public ResponseEntity<WatchlistEntryView> save(
            @Valid @RequestBody WatchlistEntryRequest request) {
        WatchlistEntry saved = watchlistService.save(request.toEntry());
        return ResponseEntity.status(201).body(WatchlistEntryView.from(saved));
    }
}
