package com.azentio.aml.web.controller;

import com.azentio.aml.domain.enums.AuditAction;
import com.azentio.aml.repository.AuditEventRepository;
import com.azentio.aml.web.dto.AuditEventView;
import com.azentio.aml.web.dto.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The immutable audit trail.
 *
 * <p>Read-only by construction: no endpoint here writes, amends or deletes an event, because the
 * evidential value of the trail depends entirely on the application being unable to rewrite it.
 * Events are produced as a side effect of the actions they describe, never by a caller.
 *
 * <p>The filter chain restricts the path to {@code AUDITOR}, {@code COMPLIANCE_OFFICER} and
 * {@code ADMIN}.
 */
@RestController
@RequestMapping("/api/v1/audit")
@Validated
@Tag(name = "Audit", description = "Immutable trail of alert and case state transitions")
public class AuditController {

    /** Default reporting window for the unfiltered trail query. */
    private static final int DEFAULT_LOOKBACK_DAYS = 7;

    private final AuditEventRepository auditEventRepository;

    public AuditController(AuditEventRepository auditEventRepository) {
        this.auditEventRepository = auditEventRepository;
    }

    @GetMapping
    @Operation(
            summary = "Audit trail over a time window",
            description = "Most recent first. Defaults to the last 7 days.")
    public PageResponse<AuditEventView> trail(
            @Parameter(description = "Window start, ISO-8601; 7 days ago when omitted")
                    @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    Instant from,
            @Parameter(description = "Window end; now when omitted") @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    Instant to,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(500) int size) {
        Instant end = to == null ? Instant.now() : to;
        Instant start = from == null ? end.minus(DEFAULT_LOOKBACK_DAYS, ChronoUnit.DAYS) : from;
        if (!start.isBefore(end)) {
            throw new IllegalArgumentException("The window start must precede its end");
        }
        return PageResponse.of(
                auditEventRepository.findByOccurredAtBetweenOrderByOccurredAtDesc(
                        start, end, PageRequest.of(page, size)),
                AuditEventView::from);
    }

    @GetMapping("/entity/{entityType}/{entityId}")
    @Operation(
            summary = "Full history of one alert, case or rule",
            description =
                    "Oldest first, so the record reads as the sequence of decisions it is. This is"
                            + " the endpoint that answers 'who changed this, when, and from what"
                            + " to what'.")
    public List<AuditEventView> forEntity(
            @Parameter(example = "Alert") @PathVariable String entityType,
            @Parameter(example = "ALT-2026-000123") @PathVariable String entityId) {
        return auditEventRepository
                .findByEntityTypeAndEntityIdOrderByOccurredAtAsc(entityType, entityId)
                .stream()
                .map(AuditEventView::from)
                .toList();
    }

    @GetMapping("/actor/{actor}")
    @Operation(summary = "Everything one identity did, most recent first")
    public PageResponse<AuditEventView> byActor(
            @PathVariable String actor,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(500) int size) {
        return PageResponse.of(
                auditEventRepository.findByActorOrderByOccurredAtDesc(
                        actor, PageRequest.of(page, size)),
                AuditEventView::from);
    }

    @GetMapping("/action/{action}")
    @Operation(
            summary = "Every occurrence of one kind of action",
            description =
                    "Used to answer targeted oversight questions such as 'show me every PII"
                            + " disclosure' or 'every rule that was disabled'.")
    public PageResponse<AuditEventView> byAction(
            @PathVariable AuditAction action,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(500) int size) {
        return PageResponse.of(
                auditEventRepository.findByActionOrderByOccurredAtDesc(
                        action, PageRequest.of(page, size)),
                AuditEventView::from);
    }
}
