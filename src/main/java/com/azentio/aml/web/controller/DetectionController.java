package com.azentio.aml.web.controller;

import com.azentio.aml.detection.DetectionEngine;
import com.azentio.aml.service.AlertService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Manual control of the detection engine.
 *
 * <p>Detection normally runs by itself - inline on ingestion and on a scheduled sweep - so these
 * endpoints exist for two specific situations: re-screening a historical window after compliance
 * has re-tuned a rule, and demonstrating the ingestion to alert flow on demand.
 *
 * <p>Restricted to compliance and admin roles. A sweep can raise a large number of alerts and
 * consumes real database capacity, so it is not something an analyst should be able to trigger at
 * will.
 */
@RestController
@RequestMapping("/api/v1/detection")
@Validated
@Tag(name = "Detection", description = "Manual re-screening and engine diagnostics")
public class DetectionController {

    /** Sweep window used when the caller does not supply one. */
    private static final int DEFAULT_SWEEP_DAYS = 2;

    private final DetectionEngine detectionEngine;

    public DetectionController(DetectionEngine detectionEngine) {
        this.detectionEngine = detectionEngine;
    }

    @PostMapping("/sweep")
    @PreAuthorize("hasAnyRole('COMPLIANCE_OFFICER','ADMIN')")
    @Operation(
            summary = "Re-screen a time window",
            description =
                    "Runs every enabled rule over the window, using the configuration currently in"
                            + " force. De-duplication still applies, so re-running a sweep folds"
                            + " repeat detections into the existing alerts instead of flooding the"
                            + " queue with copies.")
    public SweepResult sweep(
            @Parameter(description = "Window start, ISO-8601; 48 hours ago when omitted")
                    @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    Instant from,
            @Parameter(description = "Window end, exclusive; now when omitted")
                    @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    Instant to) {
        Instant end = to == null ? Instant.now() : to;
        Instant start = from == null ? end.minus(DEFAULT_SWEEP_DAYS, ChronoUnit.DAYS) : from;
        long startedAt = System.currentTimeMillis();
        AlertService.Outcome outcome = detectionEngine.sweep(start, end);
        return new SweepResult(
                start,
                end,
                outcome.created(),
                outcome.aggregated(),
                System.currentTimeMillis() - startedAt);
    }

    @PostMapping("/screen-pending")
    @PreAuthorize("hasAnyRole('COMPLIANCE_OFFICER','ADMIN')")
    @Operation(
            summary = "Screen everything still queued",
            description =
                    "Evaluates transactions that are still PENDING, anchored on the period the"
                            + " data itself describes rather than on now - so a file of historical"
                            + " transactions is judged against its own time range.")
    public SweepResult screenPending() {
        long startedAt = System.currentTimeMillis();
        AlertService.Outcome outcome = detectionEngine.screenPending();
        return new SweepResult(
                null,
                null,
                outcome.created(),
                outcome.aggregated(),
                System.currentTimeMillis() - startedAt);
    }

    @GetMapping("/rules")
    @Operation(
            summary = "Rule implementations registered with the engine",
            description =
                    "The codes the engine can actually evaluate, as opposed to the codes present"
                            + " in the configuration table.")
    public List<String> registeredRules() {
        return detectionEngine.registeredRuleCodes();
    }

    /**
     * Outcome of a manual detection run.
     *
     * @param alertsAggregated detections folded into an alert that already existed - the visible
     *     proof that de-duplication prevented a redundant alert
     */
    @Schema(name = "SweepResult", description = "Outcome of a manual detection run")
    public record SweepResult(
            Instant windowStart,
            Instant windowEnd,
            int alertsCreated,
            int alertsAggregated,
            long durationMillis) {}
}
