package com.azentio.aml.web.controller;

import com.azentio.aml.detection.DetectionEngine;
import com.azentio.aml.domain.enums.AmlTypology;
import com.azentio.aml.service.RuleConfigService;
import com.azentio.aml.web.dto.RuleConfigView;
import com.azentio.aml.web.dto.RuleTuningRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The admin API through which compliance tunes detection without a code change or a redeployment.
 *
 * <p>Thresholds, time windows, minimum occurrence counts, risk weights and the on/off switch are
 * all stored in {@code rule_configs} and re-read by the engine at evaluation time, so a change made
 * here affects the very next transaction screened.
 *
 * <p>The filter chain already restricts this path to {@code COMPLIANCE_OFFICER}, {@code ADMIN} and
 * {@code AUDITOR}; the write methods additionally exclude {@code AUDITOR}, who may inspect the
 * configuration but must not be able to change what the bank detects.
 *
 * <p>Every change bumps {@code configVersion} and is written to the audit trail with the old and
 * new values, and alerts record the version that produced them - which is what makes the effect of
 * a threshold change measurable after the fact rather than merely asserted.
 */
@RestController
@RequestMapping("/api/v1/rules")
@Validated
@Tag(name = "Rules", description = "Runtime configuration of the detection rules")
public class RuleController {

    private final RuleConfigService ruleConfigService;
    private final DetectionEngine detectionEngine;

    public RuleController(RuleConfigService ruleConfigService, DetectionEngine detectionEngine) {
        this.ruleConfigService = ruleConfigService;
        this.detectionEngine = detectionEngine;
    }

    @GetMapping
    @Operation(
            summary = "All configured rules, in execution order",
            description =
                    "'implemented' is false for a configuration row with no rule bean behind it -"
                            + " the one failure mode of a database-driven rule set that is"
                            + " otherwise invisible until the rule silently never fires.")
    public List<RuleConfigView> list() {
        Set<String> implemented = Set.copyOf(detectionEngine.registeredRuleCodes());
        return ruleConfigService.findAll().stream()
                .map(config -> RuleConfigView.from(config, implemented.contains(config.getRuleCode())))
                .toList();
    }

    @GetMapping("/{ruleCode}")
    @Operation(summary = "A single rule's configuration")
    public RuleConfigView get(@PathVariable String ruleCode) {
        Set<String> implemented = Set.copyOf(detectionEngine.registeredRuleCodes());
        return RuleConfigView.from(
                ruleConfigService.requireByCode(ruleCode), implemented.contains(ruleCode));
    }

    @PutMapping("/{ruleCode}")
    @PreAuthorize("hasAnyRole('COMPLIANCE_OFFICER','ADMIN')")
    @Operation(
            summary = "Tune a rule",
            description =
                    "Only the fields supplied are applied, so a single threshold can be moved"
                            + " without restating the rule. The change takes effect on the next"
                            + " evaluation; no restart is required.")
    public RuleConfigView tune(
            @PathVariable String ruleCode, @Valid @RequestBody RuleTuningRequest request) {
        Set<String> implemented = Set.copyOf(detectionEngine.registeredRuleCodes());
        return RuleConfigView.from(
                ruleConfigService.tune(ruleCode, request.toTuning()),
                implemented.contains(ruleCode));
    }

    @PostMapping("/{ruleCode}/enable")
    @PreAuthorize("hasAnyRole('COMPLIANCE_OFFICER','ADMIN')")
    @Operation(summary = "Enable a rule")
    public RuleConfigView enable(@PathVariable String ruleCode) {
        Set<String> implemented = Set.copyOf(detectionEngine.registeredRuleCodes());
        return RuleConfigView.from(
                ruleConfigService.setEnabled(ruleCode, true), implemented.contains(ruleCode));
    }

    @PostMapping("/{ruleCode}/disable")
    @PreAuthorize("hasAnyRole('COMPLIANCE_OFFICER','ADMIN')")
    @Operation(
            summary = "Disable a rule",
            description =
                    "Recorded separately from tuning in the audit trail, because switching a"
                            + " control off is a different governance event from re-calibrating"
                            + " it.")
    public RuleConfigView disable(@PathVariable String ruleCode) {
        Set<String> implemented = Set.copyOf(detectionEngine.registeredRuleCodes());
        return RuleConfigView.from(
                ruleConfigService.setEnabled(ruleCode, false), implemented.contains(ruleCode));
    }

    @GetMapping("/typologies")
    @Operation(
            summary = "The money-laundering typologies the platform recognises",
            description = "Reference data for dashboard filters and rule authoring.")
    public Map<String, String> typologies() {
        return java.util.Arrays.stream(AmlTypology.values())
                .collect(
                        java.util.stream.Collectors.toMap(
                                Enum::name,
                                AmlTypology::getDescription,
                                (a, b) -> a,
                                java.util.LinkedHashMap::new));
    }
}
