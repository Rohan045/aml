package com.azentio.aml.repository;

import com.azentio.aml.domain.RuleConfig;
import com.azentio.aml.domain.enums.AmlTypology;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Detection rule configuration.
 *
 * <p>Thresholds are data, not code. The engine reads {@link #findActiveRules} on each sweep, so
 * compliance can retune a threshold or disable a noisy rule without a redeployment.
 */
public interface RuleConfigRepository extends JpaRepository<RuleConfig, Long> {

    Optional<RuleConfig> findByRuleCode(String ruleCode);

    List<RuleConfig> findByTypology(AmlTypology typology);

    /**
     * Rules that are enabled and effective at the given instant, in execution order.
     *
     * <p>Null effective dates mean "always", which is the normal case; the explicit bounds exist so
     * a rule change can be scheduled and, crucially, so a historical re-run evaluates against the
     * configuration that applied at the time.
     */
    @Query(
            """
            select r
              from RuleConfig r
             where r.enabled = true
               and (r.effectiveFrom is null or r.effectiveFrom <= :at)
               and (r.effectiveTo   is null or r.effectiveTo   >  :at)
             order by r.executionOrder asc, r.ruleCode asc
            """)
    List<RuleConfig> findActiveRules(@Param("at") Instant at);
}
