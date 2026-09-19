package com.azentio.aml.repository;

import com.azentio.aml.domain.AlertTriggeredRule;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** The rules that fired for an alert, with each rule's contribution to the composite score. */
public interface AlertTriggeredRuleRepository extends JpaRepository<AlertTriggeredRule, Long> {

    List<AlertTriggeredRule> findByAlert_Id(Long alertId);

    long countByRuleCode(String ruleCode);
}
