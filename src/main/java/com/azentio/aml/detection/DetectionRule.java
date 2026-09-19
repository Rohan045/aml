package com.azentio.aml.detection;

import com.azentio.aml.domain.RuleConfig;
import com.azentio.aml.domain.enums.AmlTypology;
import java.util.List;

/**
 * Service-provider interface for a detection rule.
 *
 * <p>Implementations are stateless Spring beans discovered by the engine, so adding a typology means
 * adding one class and one {@code rule_configs} row - no change to the engine itself. The thresholds
 * a rule uses arrive in {@code config} at evaluation time rather than being read once at startup,
 * which is what allows compliance to re-tune a live rule without a redeployment.
 *
 * <p>Implementations must be thread-safe: the engine evaluates rules concurrently across accounts.
 */
public interface DetectionRule {

    /** Must match the {@code rule_code} of the configuration row that drives this rule. */
    String ruleCode();

    AmlTypology typology();

    /**
     * Evaluates the rule and returns every pattern instance it found; an empty list means the rule
     * did not fire. Implementations must not persist anything - raising and de-duplicating alerts
     * is the engine's responsibility.
     *
     * @param config the tuning in force for this evaluation
     * @param context the window, watchlist snapshot and (when streaming) the triggering transaction
     */
    List<RuleFinding> evaluate(RuleConfig config, DetectionContext context);

    /**
     * Whether the rule can be evaluated for a single arriving transaction. Rules that need a
     * cross-account aggregate can opt out of the streaming path and run in the sweep only.
     */
    default boolean supportsStreaming() {
        return true;
    }
}
