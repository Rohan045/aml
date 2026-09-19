package com.azentio.aml.web.controller;

import com.azentio.aml.service.DashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Compliance dashboard aggregates: alert volume by typology and severity, queue composition, open
 * caseload, SAR count and time-to-disposition.
 *
 * <p>Readable by every authenticated role, auditors included - these are counts, not customer
 * records, and an oversight function that cannot see the volume of work is not oversight.
 */
@RestController
@RequestMapping("/api/v1/dashboard")
@Validated
@Tag(name = "Dashboard", description = "Detection and investigation metrics")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping
    @Operation(
            summary = "Dashboard snapshot",
            description =
                    "Alert volumes by typology and severity for the heatmap, plus backlog and"
                            + " productivity metrics. Every figure is computed from the same"
                            + " tables the investigation workflow writes to, so the dashboard"
                            + " cannot disagree with the case file.")
    public DashboardService.DashboardSnapshot snapshot(
            @Parameter(description = "Reporting window in days; 30 when omitted")
                    @RequestParam(required = false)
                    @Min(1)
                    @Max(3650)
                    Integer lookbackDays) {
        return dashboardService.snapshot(lookbackDays);
    }
}
