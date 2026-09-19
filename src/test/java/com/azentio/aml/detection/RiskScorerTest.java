package com.azentio.aml.detection;

import static com.azentio.aml.detection.TestFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.azentio.aml.config.SentinelProperties;
import com.azentio.aml.domain.Customer;
import com.azentio.aml.domain.enums.AmlTypology;
import com.azentio.aml.domain.enums.RiskRating;
import java.util.List;
import org.junit.jupiter.api.Test;

class RiskScorerTest {

    @Test
    void scoreAddsRuleContributionsCustomerRiskAndPepUplift() {
        SentinelProperties properties = new SentinelProperties();
        properties.getDetection().setPepUplift(12);
        RiskScorer scorer = new RiskScorer(properties);
        Customer customer = customer("CUST-SCORE");
        customer.setRiskRating(RiskRating.HIGH);
        customer.setPoliticallyExposed(true);
        RuleFinding first = RuleFinding.builder().typology(AmlTypology.STRUCTURING).ruleWeight(20).contextUplift(5).build();
        RuleFinding second = RuleFinding.builder().typology(AmlTypology.RAPID_MOVEMENT).ruleWeight(15).build();

        RiskScorer.Breakdown breakdown = scorer.score(List.of(first, second), customer);

        assertThat(breakdown.ruleContribution()).isEqualTo(40);
        assertThat(breakdown.profileUplift()).isEqualTo(30);
        assertThat(breakdown.pepUplift()).isEqualTo(12);
        assertThat(breakdown.rawScore()).isEqualTo(82);
        assertThat(breakdown.score()).isEqualTo(82);
        assertThat(breakdown.describe()).contains("40 from triggered rules", "risk rating", "politically exposed");
    }

    @Test
    void scoreCapsAtConfiguredMaximumAndNeverLetsNegativeRulesReduceBelowZero() {
        SentinelProperties properties = new SentinelProperties();
        properties.getDetection().setMaxRiskScore(50);
        RiskScorer scorer = new RiskScorer(properties);
        RuleFinding large = RuleFinding.builder().typology(AmlTypology.HIGH_RISK_COUNTERPARTY).ruleWeight(80).contextUplift(10).build();
        RuleFinding negative = RuleFinding.builder().typology(AmlTypology.ROUND_AMOUNT_PATTERN).ruleWeight(-10).build();

        RiskScorer.Breakdown breakdown = scorer.score(List.of(large, negative), null);

        assertThat(breakdown.ruleContribution()).isEqualTo(90);
        assertThat(breakdown.rawScore()).isEqualTo(90);
        assertThat(breakdown.score()).isEqualTo(50);
        assertThat(breakdown.wasCapped()).isTrue();
        assertThat(breakdown.describe()).contains("capped at the maximum");
    }
}
