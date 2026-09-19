package com.azentio.aml.service;

import com.azentio.aml.common.exception.NotFoundException;
import com.azentio.aml.config.CacheConfig;
import com.azentio.aml.domain.RuleConfig;
import com.azentio.aml.domain.enums.AlertSeverity;
import com.azentio.aml.domain.enums.AuditAction;
import com.azentio.aml.repository.RuleConfigRepository;
import com.azentio.aml.security.CurrentUser;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runtime rule configuration: compliance tunes thresholds, windows and weights, or switches a rule
 * off entirely, without a code change or redeployment.
 *
 * <p>Every tuning change bumps {@code configVersion} and is written to the audit trail with the old
 * and new values. Alerts stamp the version that produced them, so the effect of a threshold change
 * on alert volume can be measured after the fact - the A/B testing requirement.
 *
 * <p>The active rule set is cached and evicted on every write, so the engine picks up a change on
 * its next evaluation rather than at the next restart.
 */
@Service
public class RuleConfigService {

    private static final Logger log = LoggerFactory.getLogger(RuleConfigService.class);
    private static final String ENTITY = "RuleConfig";

    private final RuleConfigRepository ruleConfigRepository;
    private final AuditService auditService;

    public RuleConfigService(
            RuleConfigRepository ruleConfigRepository, AuditService auditService) {
        this.ruleConfigRepository = ruleConfigRepository;
        this.auditService = auditService;
    }

    /** Rules in force at {@code at}, in execution order so cheap rules run first. */
    @Transactional(readOnly = true)
    @Cacheable(cacheNames = CacheConfig.ACTIVE_RULES, key = "'active'")
    public List<RuleConfig> activeRules(Instant at) {
        return ruleConfigRepository.findActiveRules(at).stream()
                .sorted(Comparator.comparing(RuleConfig::getExecutionOrder))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<RuleConfig> findAll() {
        return ruleConfigRepository.findAll().stream()
                .sorted(Comparator.comparing(RuleConfig::getExecutionOrder))
                .toList();
    }

    @Transactional(readOnly = true)
    public RuleConfig requireByCode(String ruleCode) {
        return ruleConfigRepository
                .findByRuleCode(ruleCode)
                .orElseThrow(() -> NotFoundException.of("Rule", ruleCode));
    }

    /**
     * Applies a tuning change. Only non-null fields on {@code tuning} are applied, so a caller can
     * adjust one threshold without having to restate the whole rule.
     */
    @Transactional
    @CacheEvict(cacheNames = CacheConfig.ACTIVE_RULES, allEntries = true)
    public RuleConfig tune(String ruleCode, RuleTuning tuning) {
        RuleConfig rule = requireByCode(ruleCode);
        String before = describe(rule);

        if (tuning.thresholdAmount() != null) {
            rule.setThresholdAmount(tuning.thresholdAmount());
        }
        if (tuning.thresholdAmountMin() != null) {
            rule.setThresholdAmountMin(tuning.thresholdAmountMin());
        }
        if (tuning.minOccurrences() != null) {
            rule.setMinOccurrences(tuning.minOccurrences());
        }
        if (tuning.timeWindowHours() != null) {
            rule.setTimeWindowHours(tuning.timeWindowHours());
        }
        if (tuning.deviationMultiplier() != null) {
            rule.setDeviationMultiplier(tuning.deviationMultiplier());
        }
        if (tuning.lookbackDays() != null) {
            rule.setLookbackDays(tuning.lookbackDays());
        }
        if (tuning.ratioThreshold() != null) {
            rule.setRatioThreshold(tuning.ratioThreshold());
        }
        if (tuning.dedupeWindowHours() != null) {
            rule.setDedupeWindowHours(tuning.dedupeWindowHours());
        }
        if (tuning.riskWeight() != null) {
            rule.setRiskWeight(tuning.riskWeight());
        }
        if (tuning.severity() != null) {
            rule.setSeverity(tuning.severity());
        }
        if (tuning.executionOrder() != null) {
            rule.setExecutionOrder(tuning.executionOrder());
        }
        if (tuning.parameters() != null) {
            rule.setParameters(tuning.parameters());
        }
        if (tuning.enabled() != null) {
            rule.setEnabled(tuning.enabled());
        }

        validate(rule);
        stampTuning(rule);
        RuleConfig saved = ruleConfigRepository.save(rule);

        auditService.record(
                ENTITY,
                ruleCode,
                AuditAction.RULE_TUNED,
                before,
                describe(saved),
                AuditService.detailsOf(
                        "ruleCode", ruleCode,
                        "configVersion", saved.getConfigVersion(),
                        "tunedBy", saved.getLastTunedBy()));
        log.info(
                "Rule {} tuned to version {} by {}",
                ruleCode,
                saved.getConfigVersion(),
                saved.getLastTunedBy());
        return saved;
    }

    /** Toggling is separated from tuning so the audit trail distinguishes the two actions. */
    @Transactional
    @CacheEvict(cacheNames = CacheConfig.ACTIVE_RULES, allEntries = true)
    public RuleConfig setEnabled(String ruleCode, boolean enabled) {
        RuleConfig rule = requireByCode(ruleCode);
        boolean wasEnabled = Boolean.TRUE.equals(rule.getEnabled());
        if (wasEnabled == enabled) {
            return rule;
        }
        rule.setEnabled(enabled);
        stampTuning(rule);
        RuleConfig saved = ruleConfigRepository.save(rule);

        auditService.record(
                ENTITY,
                ruleCode,
                enabled ? AuditAction.RULE_ENABLED : AuditAction.RULE_DISABLED,
                String.valueOf(wasEnabled),
                String.valueOf(enabled),
                AuditService.detailsOf("ruleCode", ruleCode, "enabled", enabled));
        log.info("Rule {} {} by {}", ruleCode, enabled ? "enabled" : "disabled", CurrentUser.username());
        return saved;
    }

    private void stampTuning(RuleConfig rule) {
        rule.setConfigVersion(rule.getConfigVersion() == null ? 1 : rule.getConfigVersion() + 1);
        rule.setLastTunedBy(CurrentUser.username());
        rule.setLastTunedAt(Instant.now());
    }

    /**
     * Guards against a tuning change that would make a rule fire on everything or nothing, which
     * would be far more damaging than a rejected request.
     */
    private void validate(RuleConfig rule) {
        if (rule.getThresholdAmount() != null
                && rule.getThresholdAmountMin() != null
                && rule.getThresholdAmountMin().compareTo(rule.getThresholdAmount()) > 0) {
            throw new IllegalArgumentException(
                    "thresholdAmountMin must not exceed thresholdAmount for rule "
                            + rule.getRuleCode());
        }
        if (rule.getRiskWeight() != null && rule.getRiskWeight() < 0) {
            throw new IllegalArgumentException("riskWeight must not be negative");
        }
        if (rule.getRatioThreshold() != null
                && (rule.getRatioThreshold().compareTo(BigDecimal.ZERO) <= 0
                        || rule.getRatioThreshold().compareTo(BigDecimal.ONE) > 0)) {
            throw new IllegalArgumentException("ratioThreshold must be between 0 (exclusive) and 1");
        }
        if (rule.getMinOccurrences() != null && rule.getMinOccurrences() < 1) {
            throw new IllegalArgumentException("minOccurrences must be at least 1");
        }
        if (rule.getTimeWindowHours() != null && rule.getTimeWindowHours() < 1) {
            throw new IllegalArgumentException("timeWindowHours must be at least 1");
        }
    }

    private static String describe(RuleConfig rule) {
        return AuditService.detailsOf(
                "enabled", rule.getEnabled(),
                "thresholdAmount", rule.getThresholdAmount(),
                "thresholdAmountMin", rule.getThresholdAmountMin(),
                "minOccurrences", rule.getMinOccurrences(),
                "timeWindowHours", rule.getTimeWindowHours(),
                "deviationMultiplier", rule.getDeviationMultiplier(),
                "ratioThreshold", rule.getRatioThreshold(),
                "riskWeight", rule.getRiskWeight(),
                "severity", rule.getSeverity(),
                "configVersion", rule.getConfigVersion());
    }

    /** Partial tuning payload; every field is optional and only non-null values are applied. */
    public record RuleTuning(
            Boolean enabled,
            BigDecimal thresholdAmount,
            BigDecimal thresholdAmountMin,
            Integer minOccurrences,
            Integer timeWindowHours,
            BigDecimal deviationMultiplier,
            Integer lookbackDays,
            BigDecimal ratioThreshold,
            Integer dedupeWindowHours,
            Integer riskWeight,
            AlertSeverity severity,
            Integer executionOrder,
            String parameters) {}
}
