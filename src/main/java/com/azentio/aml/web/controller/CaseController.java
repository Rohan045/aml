package com.azentio.aml.web.controller;

import com.azentio.aml.domain.AmlCase;
import com.azentio.aml.domain.enums.CaseStatus;
import com.azentio.aml.security.CurrentUser;
import com.azentio.aml.service.CaseService;
import com.azentio.aml.web.dto.AlertSummary;
import com.azentio.aml.web.dto.AssignRequest;
import com.azentio.aml.web.dto.CaseDetail;
import com.azentio.aml.web.dto.CaseNoteRequest;
import com.azentio.aml.web.dto.CaseNoteView;
import com.azentio.aml.web.dto.CaseStatusRequest;
import com.azentio.aml.web.dto.CaseSummary;
import com.azentio.aml.web.dto.CloseCaseRequest;
import com.azentio.aml.web.dto.OpenCaseRequest;
import com.azentio.aml.web.dto.PageResponse;
import com.azentio.aml.web.dto.SarFilingRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
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
 * Case management: the workflow that turns a queue of alerts into an investigation with a
 * defensible outcome.
 *
 * <p>Escalation authority is graded. Any analyst may open a case and add notes, but closing one or
 * filing a SAR is restricted to senior roles - the decision to tell a regulator that nothing
 * happened here carries the same weight as the decision to tell them something did.
 */
@RestController
@RequestMapping("/api/v1/cases")
@Validated
@Tag(name = "Cases", description = "Investigation workflow, notes, SAR drafting and closure")
public class CaseController {

    private static final String ANALYST_ROLES =
            "hasAnyRole('ANALYST','SENIOR_ANALYST','COMPLIANCE_OFFICER','ADMIN')";
    private static final String SENIOR_ROLES =
            "hasAnyRole('SENIOR_ANALYST','COMPLIANCE_OFFICER','ADMIN')";

    private final CaseService caseService;

    public CaseController(CaseService caseService) {
        this.caseService = caseService;
    }

    @GetMapping
    @Operation(
            summary = "Case queue",
            description = "Open investigations by default; pass statuses to widen or narrow.")
    public PageResponse<CaseSummary> queue(
            @Parameter(description = "Case statuses to include; open statuses when omitted")
                    @RequestParam(required = false)
                    List<CaseStatus> status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int size) {
        Pageable pageable = PageRequest.of(page, size);
        return PageResponse.of(caseService.queue(status, pageable), CaseSummary::from);
    }

    @GetMapping("/{id}")
    @Operation(
            summary = "Case file",
            description = "The investigation with its linked alerts, notes and closure record.")
    public CaseDetail detail(@PathVariable Long id) {
        AmlCase amlCase = caseService.requireById(id);
        return CaseDetail.from(
                amlCase,
                caseService.alertsOf(id).stream().map(AlertSummary::from).toList(),
                caseService.notesOf(id).stream().map(CaseNoteView::from).toList(),
                CurrentUser.canViewFullPii());
    }

    @PostMapping
    @PreAuthorize(ANALYST_ROLES)
    @Operation(
            summary = "Open a case over one or more alerts",
            description = "All the alerts must concern the same customer.")
    public ResponseEntity<CaseDetail> open(@Valid @RequestBody OpenCaseRequest request) {
        AmlCase opened =
                caseService.open(request.title(), request.description(), request.alertIds());
        return ResponseEntity.created(URI.create("/api/v1/cases/" + opened.getId()))
                .body(detail(opened.getId()));
    }

    @PostMapping("/{id}/alerts/{alertId}")
    @PreAuthorize(ANALYST_ROLES)
    @Operation(summary = "Link a further alert to an open case")
    public CaseDetail linkAlert(@PathVariable Long id, @PathVariable Long alertId) {
        caseService.linkAlert(id, alertId);
        return detail(id);
    }

    @PostMapping("/{id}/assign")
    @PreAuthorize(ANALYST_ROLES)
    @Operation(summary = "Assign a case to an analyst")
    public CaseSummary assign(@PathVariable Long id, @Valid @RequestBody AssignRequest request) {
        return CaseSummary.from(caseService.assign(id, request.assignee()));
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize(ANALYST_ROLES)
    @Operation(
            summary = "Move a case through the workflow",
            description = "Closure is handled by its own endpoint, which requires a reason.")
    public CaseSummary changeStatus(
            @PathVariable Long id, @Valid @RequestBody CaseStatusRequest request) {
        return CaseSummary.from(caseService.changeStatus(id, request.status()));
    }

    @PostMapping("/{id}/notes")
    @PreAuthorize(ANALYST_ROLES)
    @Operation(
            summary = "Add an investigation note",
            description = "The author is taken from the authenticated identity.")
    public ResponseEntity<CaseNoteView> addNote(
            @PathVariable Long id, @Valid @RequestBody CaseNoteRequest request) {
        return ResponseEntity.status(201)
                .body(CaseNoteView.from(caseService.addNote(id, request.note())));
    }

    @GetMapping("/{id}/notes")
    @Operation(summary = "Investigation notes, oldest first")
    public List<CaseNoteView> notes(@PathVariable Long id) {
        return caseService.notesOf(id).stream().map(CaseNoteView::from).toList();
    }

    @GetMapping("/{id}/sar-draft")
    @PreAuthorize(SENIOR_ROLES)
    @Operation(
            summary = "Draft a SAR narrative",
            description =
                    "Assembles a narrative from the evidence already on the case. It is a draft"
                            + " only - nothing is filed and the case is not modified.")
    public String sarDraft(@PathVariable Long id) {
        return caseService.draftSarNarrative(id);
    }

    @PostMapping("/{id}/sar")
    @PreAuthorize("hasAnyRole('COMPLIANCE_OFFICER','ADMIN')")
    @Operation(
            summary = "Record a filed SAR",
            description = "Stores the regulator's reference and moves the case to SAR_FILED.")
    public CaseDetail fileSar(
            @PathVariable Long id, @Valid @RequestBody SarFilingRequest request) {
        caseService.fileSar(id, request.sarReference());
        return detail(id);
    }

    @PostMapping("/{id}/close")
    @PreAuthorize(SENIOR_ROLES)
    @Operation(
            summary = "Close an investigation",
            description =
                    "Nothing is removed: the case, its alerts, notes and evidence all remain,"
                            + " with the closure reason and the closing analyst recorded.")
    public CaseDetail close(@PathVariable Long id, @Valid @RequestBody CloseCaseRequest request) {
        caseService.close(id, request.closureReason(), request.narrative());
        return detail(id);
    }
}
