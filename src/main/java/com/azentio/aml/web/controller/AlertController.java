package com.azentio.aml.web.controller;

import com.azentio.aml.domain.Alert;
import com.azentio.aml.domain.enums.AlertStatus;
import com.azentio.aml.domain.enums.AmlTypology;
import com.azentio.aml.domain.enums.AuditAction;
import com.azentio.aml.security.CurrentUser;
import com.azentio.aml.service.AlertService;
import com.azentio.aml.service.AuditService;
import com.azentio.aml.web.dto.AlertDetail;
import com.azentio.aml.web.dto.AlertStatusRequest;
import com.azentio.aml.web.dto.AlertSummary;
import com.azentio.aml.web.dto.AssignRequest;
import com.azentio.aml.web.dto.DispositionRequest;
import com.azentio.aml.web.dto.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The analyst's alert queue and triage workflow.
 *
 * <p>Reads are open to every authenticated role, including {@code AUDITOR}, because an auditor must
 * be able to see what the system flagged. Writes exclude {@code AUDITOR} by design: the audit role
 * observes the investigation, it does not participate in it, and a read-only role that can close
 * alerts is not read-only.
 *
 * <p>Opening an alert detail is itself recorded when it discloses unmasked PII, so the question
 * "who looked at this customer's identity, and when" has an answer (business rule 8).
 */
@RestController
@RequestMapping("/api/v1/alerts")
@Validated
@Tag(name = "Alerts", description = "Risk-scored alert queue and analyst triage")
public class AlertController {

    private static final String ANALYST_ROLES =
            "hasAnyRole('ANALYST','SENIOR_ANALYST','COMPLIANCE_OFFICER','ADMIN')";

    private final AlertService alertService;
    private final AuditService auditService;

    public AlertController(AlertService alertService, AuditService auditService) {
        this.alertService = alertService;
        this.auditService = auditService;
    }

    @GetMapping
    @Operation(
            summary = "Alert queue",
            description =
                    "Open alerts by default, highest risk score first so the most serious work"
                            + " sorts to the top. Pass one or more statuses to narrow the queue.")
    public PageResponse<AlertSummary> queue(
            @Parameter(description = "Alert statuses to include; open statuses when omitted")
                    @RequestParam(required = false)
                    List<AlertStatus> status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int size) {
        Pageable pageable = PageRequest.of(page, size);
        return PageResponse.of(alertService.queue(status, pageable), AlertSummary::from);
    }

    @GetMapping("/mine")
    @Operation(
            summary = "My open alerts",
            description = "Open alerts assigned to the authenticated analyst.")
    public PageResponse<AlertSummary> mine(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int size) {
        Pageable pageable = PageRequest.of(page, size);
        return PageResponse.of(
                alertService.assignedTo(CurrentUser.username(), pageable), AlertSummary::from);
    }

    @GetMapping("/by-typology/{typology}")
    @Operation(
            summary = "Alerts for one money-laundering typology",
            description = "Used by the dashboard heatmap to drill into a single pattern.")
    public PageResponse<AlertSummary> byTypology(
            @PathVariable AmlTypology typology,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int size) {
        Pageable pageable = PageRequest.of(page, size);
        return PageResponse.of(
                alertService.byTypology(typology, pageable), AlertSummary::from);
    }

    @GetMapping("/{id}")
    @Operation(
            summary = "Alert detail",
            description =
                    "The full explanation: why the pattern was flagged, which rules fired with"
                            + " which thresholds, and the transactions cited as evidence.")
    public AlertDetail detail(@PathVariable Long id) {
        Alert alert = alertService.requireDetail(id);
        boolean unmasked = CurrentUser.canViewFullPii();
        if (unmasked) {
            auditService.record(
                    "Customer",
                    alert.getCustomer().getCustomerId(),
                    AuditAction.PII_REVEALED,
                    AuditService.detailsOf(
                            "context", "alert-detail", "alert", alert.getAlertReference()));
        }
        return AlertDetail.from(alert, unmasked);
    }

    @PostMapping("/{id}/assign")
    @PreAuthorize(ANALYST_ROLES)
    @Operation(summary = "Assign an alert to an analyst")
    public AlertSummary assign(@PathVariable Long id, @Valid @RequestBody AssignRequest request) {
        return AlertSummary.from(alertService.assign(id, request.assignee()));
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize(ANALYST_ROLES)
    @Operation(
            summary = "Move an alert through triage",
            description =
                    "Closing is not available here: an alert is closed only through the"
                            + " disposition endpoint, which requires a reason.")
    public AlertSummary changeStatus(
            @PathVariable Long id, @Valid @RequestBody AlertStatusRequest request) {
        return AlertSummary.from(alertService.changeStatus(id, request.status()));
    }

    @PostMapping("/{id}/disposition")
    @PreAuthorize("hasAnyRole('SENIOR_ANALYST','COMPLIANCE_OFFICER','ADMIN')")
    @Operation(
            summary = "Close an alert with a disposition",
            description =
                    "The alert is never deleted. Its evidence, the disposition reason and the"
                            + " deciding analyst all remain queryable afterwards.")
    public ResponseEntity<AlertDetail> disposition(
            @PathVariable Long id, @Valid @RequestBody DispositionRequest request) {
        alertService.disposition(id, request.disposition(), request.reason());
        // Re-read through the detail projection so the response carries evidence and rules.
        return ResponseEntity.ok(
                AlertDetail.from(alertService.requireDetail(id), CurrentUser.canViewFullPii()));
    }
}
