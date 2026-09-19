package com.azentio.aml.detection;

import com.azentio.aml.config.SentinelProperties;
import com.azentio.aml.domain.Customer;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Turns the rules that fired into the single 0-100 score that orders the analyst queue (business
 * rule 7).
 *
 * <p>The score is a weighted sum, not an average: two independent typologies firing on one customer
 * is materially more suspicious than either alone, and an average would dilute exactly the cases
 * that matter most. Customer context is then added on top, because the same pattern carries
 * different risk for a politically exposed person than for a salaried retail customer.
 *
 * <p>Every component is reported back through {@link Breakdown} so the number shown to an analyst
 * can always be decomposed into the reasons that produced it - a score nobody can explain is a score
 * nobody will act on.
 */
@Component
public class RiskScorer {

    private final SentinelProperties properties;

    public RiskScorer(SentinelProperties properties) {
        this.properties = properties;
    }

    /**
     * @param findings the rules that fired for one alert
     * @param customer the subject, used for the profile uplift; may be null if not yet loaded
     */
    public Breakdown score(List<RuleFinding> findings, Customer customer) {
        int ruleContribution = 0;
        for (RuleFinding finding : findings) {
            ruleContribution += finding.contributedScore();
        }

        int profileUplift = 0;
        int pepUplift = 0;
        if (customer != null) {
            if (customer.getRiskRating() != null) {
                profileUplift = customer.getRiskRating().getWeight();
            }
            if (Boolean.TRUE.equals(customer.getPoliticallyExposed())) {
                pepUplift = properties.getDetection().getPepUplift();
            }
        }

        int raw = ruleContribution + profileUplift + pepUplift;
        int capped = Math.max(0, Math.min(properties.getDetection().getMaxRiskScore(), raw));
        return new Breakdown(capped, raw, ruleContribution, profileUplift, pepUplift);
    }

    /**
     * Decomposition of a composite score.
     *
     * @param score the final, capped value stored on the alert
     * @param rawScore the uncapped total; when it exceeds {@code score} the alert was already at
     *     maximum severity and further evidence could not raise it
     */
    public record Breakdown(
            int score, int rawScore, int ruleContribution, int profileUplift, int pepUplift) {

        public boolean wasCapped() {
            return rawScore > score;
        }

        /** One-line justification appended to the alert explanation. */
        public String describe() {
            StringBuilder text = new StringBuilder();
            text.append("Risk score ")
                    .append(score)
                    .append("/100 = ")
                    .append(ruleContribution)
                    .append(" from triggered rules");
            if (profileUplift > 0) {
                text.append(" + ").append(profileUplift).append(" for the customer's risk rating");
            }
            if (pepUplift > 0) {
                text.append(" + ").append(pepUplift).append(" for politically exposed status");
            }
            if (wasCapped()) {
                text.append(" (raw total ").append(rawScore).append(", capped at the maximum)");
            }
            return text.append('.').toString();
        }
    }
}
