package com.azentio.aml.detection;

import com.azentio.aml.domain.Transaction;
import com.azentio.aml.domain.enums.AlertSeverity;
import com.azentio.aml.domain.enums.AmlTypology;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.Builder;
import lombok.Getter;
import lombok.Singular;

/**
 * One rule firing, before it becomes (or is folded into) an {@link com.azentio.aml.domain.Alert}.
 *
 * <p>A finding is deliberately self-describing: it carries the plain-English {@code explanation},
 * the {@code detailsJson} snapshot of thresholds versus observed values, and the evidence
 * transactions. That is what lets the alert answer "why was this flagged?" without the analyst
 * having to reconstruct the rule's reasoning.
 *
 * <p>{@code discriminator} is the part of the de-duplication key that identifies the specific
 * pattern instance - an account for structuring, a jurisdiction for a sanctions hit, a single
 * transaction for the CTR threshold. Two findings sharing a customer, typology, discriminator and
 * time bucket are the same underlying pattern and must aggregate into one alert.
 */
@Getter
@Builder
public class RuleFinding {

    private final String ruleCode;
    private final String ruleName;
    private final Integer ruleVersion;
    private final AmlTypology typology;
    private final AlertSeverity severity;

    /** Configured weight of the rule, before contextual uplift. */
    private final int ruleWeight;

    /** Extra points from context such as a sanctioned counterparty's own risk weight. */
    @Builder.Default private final int contextUplift = 0;

    private final String customerId;

    /** Null for customer-level typologies that span several accounts. */
    private final String accountId;

    private final String title;
    private final String explanation;
    private final String detailsJson;

    /** Distinguishes one instance of the pattern from another within the same typology. */
    private final String discriminator;

    private final Instant windowStart;
    private final Instant windowEnd;
    private final Instant detectedAt;

    private final BigDecimal totalAmount;
    private final int transactionCount;

    @Singular("evidence")
    private final List<EvidenceItem> evidenceItems;

    /** Points this rule contributes to the composite alert score. */
    public int contributedScore() {
        return Math.max(0, ruleWeight + contextUplift);
    }

    public List<Transaction> evidenceTransactions() {
        List<Transaction> transactions = new ArrayList<>(evidenceItems.size());
        for (EvidenceItem item : evidenceItems) {
            transactions.add(item.transaction());
        }
        return transactions;
    }

    /**
     * A transaction supporting the finding.
     *
     * @param role why this transaction matters to the rule, e.g. {@code INFLOW} or {@code
     *     STRUCTURING_LEG}; shown in the evidence pack so the narrative reads in order
     */
    public record EvidenceItem(Transaction transaction, String role, String note) {

        public static EvidenceItem of(Transaction transaction, String role) {
            return new EvidenceItem(transaction, role, null);
        }

        public static EvidenceItem of(Transaction transaction, String role, String note) {
            return new EvidenceItem(transaction, role, note);
        }
    }
}
