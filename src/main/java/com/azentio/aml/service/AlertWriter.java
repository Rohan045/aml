package com.azentio.aml.service;

import com.azentio.aml.common.exception.NotFoundException;
import com.azentio.aml.detection.RiskScorer;
import com.azentio.aml.detection.RuleFinding;
import com.azentio.aml.domain.Account;
import com.azentio.aml.domain.Alert;
import com.azentio.aml.domain.AlertEvidence;
import com.azentio.aml.domain.AlertTriggeredRule;
import com.azentio.aml.domain.Customer;
import com.azentio.aml.domain.Transaction;
import com.azentio.aml.domain.enums.AlertStatus;
import com.azentio.aml.domain.enums.AuditAction;
import com.azentio.aml.repository.AccountRepository;
import com.azentio.aml.repository.AlertRepository;
import com.azentio.aml.repository.CustomerRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional write side of alert raising.
 *
 * <p>Separated from {@link AlertService} deliberately: the insert must run in its own transaction so
 * that a unique-constraint rejection (the losing side of a de-duplication race) marks only that
 * transaction for rollback and leaves the caller free to retry down the fold path. Calling a
 * {@code REQUIRES_NEW} method on {@code this} would bypass the proxy and silently defeat that,
 * so the boundary is a separate bean rather than a private method.
 */
@Component
class AlertWriter {

    private static final Logger log = LoggerFactory.getLogger(AlertWriter.class);
    private static final String ENTITY = "Alert";

    private final AlertRepository alertRepository;
    private final CustomerRepository customerRepository;
    private final AccountRepository accountRepository;
    private final RiskScorer riskScorer;
    private final AuditService auditService;

    AlertWriter(
            AlertRepository alertRepository,
            CustomerRepository customerRepository,
            AccountRepository accountRepository,
            RiskScorer riskScorer,
            AuditService auditService) {
        this.alertRepository = alertRepository;
        this.customerRepository = customerRepository;
        this.accountRepository = accountRepository;
        this.riskScorer = riskScorer;
        this.auditService = auditService;
    }

    /**
     * Folds a repeat detection into the alert that already covers this pattern.
     *
     * @return false if no such alert exists, meaning the caller must insert one
     */
    @Transactional
    public boolean foldIntoExisting(
            String dedupeKey, List<RuleFinding> findings, Instant detectedAt) {
        if (alertRepository.recordRepeatDetection(dedupeKey, detectedAt) == 0) {
            return false;
        }
        alertRepository.findByDedupeKey(dedupeKey).ifPresent(alert -> mergeInto(alert, findings));
        return true;
    }

    /**
     * Attaches rules and evidence the existing alert has not already recorded.
     *
     * <p>A repeat detection normally carries fresh transactions; attaching them keeps the evidence
     * pack complete without multiplying alerts. Items already present are skipped, so the unique
     * constraints on {@code (alert_id, rule_code)} and {@code (alert_id, transaction_id)} are never
     * challenged.
     */
    private void mergeInto(Alert alert, List<RuleFinding> findings) {
        Set<String> existingRules = new HashSet<>();
        alert.getTriggeredRules().forEach(rule -> existingRules.add(rule.getRuleCode()));
        Set<Long> existingEvidence = new HashSet<>();
        alert.getEvidence()
                .forEach(
                        item -> {
                            if (item.getTransaction() != null) {
                                existingEvidence.add(item.getTransaction().getId());
                            }
                        });

        boolean changed = false;
        for (RuleFinding finding : findings) {
            if (existingRules.add(finding.getRuleCode())) {
                alert.addTriggeredRule(toTriggeredRule(finding));
                changed = true;
            }
            for (RuleFinding.EvidenceItem item : finding.getEvidenceItems()) {
                Long transactionId = item.transaction().getId();
                if (transactionId != null && existingEvidence.add(transactionId)) {
                    alert.addEvidence(toEvidence(item));
                    changed = true;
                }
            }
        }
        if (!changed) {
            return;
        }
        // Newly attached rules may push the alert into a higher severity band, but a re-detection
        // must never lower a score an analyst has already triaged against.
        RiskScorer.Breakdown breakdown = riskScorer.score(findings, alert.getCustomer());
        alert.applyRiskScore(Math.max(alert.getRiskScore(), breakdown.score()));
        alert.setTransactionCount(alert.getEvidence().size());
        alertRepository.save(alert);
        log.debug(
                "Alert {} absorbed a repeat detection ({} evidence items)",
                alert.getAlertReference(),
                alert.getEvidence().size());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Alert insert(String dedupeKey, List<RuleFinding> findings) {
        RuleFinding primary = findings.get(0);
        Customer customer =
                customerRepository
                        .findById(primary.getCustomerId())
                        .orElseThrow(
                                () -> NotFoundException.of("Customer", primary.getCustomerId()));
        Account account =
                primary.getAccountId() == null
                        ? null
                        : accountRepository.findById(primary.getAccountId()).orElse(null);

        RiskScorer.Breakdown breakdown = riskScorer.score(findings, customer);

        Alert alert =
                Alert.builder()
                        .alertReference(nextAlertReference())
                        .customer(customer)
                        .account(account)
                        .typology(primary.getTypology())
                        .status(AlertStatus.NEW)
                        .title(primary.getTitle())
                        .explanation(buildExplanation(findings, breakdown))
                        .dedupeKey(dedupeKey)
                        .occurrenceCount(1)
                        .detectionWindowStart(primary.getWindowStart())
                        .detectionWindowEnd(primary.getWindowEnd())
                        .firstDetectedAt(primary.getDetectedAt())
                        .lastDetectedAt(primary.getDetectedAt())
                        .totalAmount(totalAmountOf(findings))
                        .build();
        alert.applyRiskScore(breakdown.score());

        Set<String> ruleCodes = new HashSet<>();
        Set<String> evidenceIds = new HashSet<>();
        for (RuleFinding finding : findings) {
            if (ruleCodes.add(finding.getRuleCode())) {
                alert.addTriggeredRule(toTriggeredRule(finding));
            }
            for (RuleFinding.EvidenceItem item : finding.getEvidenceItems()) {
                if (evidenceIds.add(item.transaction().getExternalTxnId())) {
                    alert.addEvidence(toEvidence(item));
                }
            }
        }
        alert.setTransactionCount(alert.getEvidence().size());

        Alert saved = alertRepository.save(alert);
        auditService.record(
                ENTITY,
                saved.getAlertReference(),
                AuditAction.CREATED,
                null,
                AlertStatus.NEW.name(),
                AuditService.detailsOf(
                        "typology", saved.getTypology(),
                        "riskScore", saved.getRiskScore(),
                        "severity", saved.getSeverity(),
                        "customerId", primary.getCustomerId(),
                        "accountId", primary.getAccountId(),
                        "rules", String.join(",", ruleCodes)));
        log.info(
                "Raised alert {} ({}, score {}) for customer {}",
                saved.getAlertReference(),
                saved.getTypology(),
                saved.getRiskScore(),
                primary.getCustomerId());
        return saved;
    }

    /**
     * Human-facing reference, built from the detection instant plus a random suffix.
     *
     * <p>A database sequence would be prettier but becomes a contention point on a concurrent,
     * potentially multi-instance detection path. Uniqueness is guaranteed by the unique constraint
     * on the column; a collision simply loses the insert race and folds, which is already the
     * handled path.
     */
    private String nextAlertReference() {
        Instant now = Instant.now();
        String suffix =
                Long.toString(now.toEpochMilli(), 36).toUpperCase()
                        + Integer.toString(
                                        1296 + ThreadLocalRandom.current().nextInt(1296), 36)
                                .toUpperCase()
                                .substring(1);
        return "ALT-" + now.atZone(ZoneOffset.UTC).getYear() + "-" + suffix;
    }

    /** Concatenates each rule's reasoning and appends the score decomposition. */
    private String buildExplanation(List<RuleFinding> findings, RiskScorer.Breakdown breakdown) {
        StringBuilder text = new StringBuilder();
        for (RuleFinding finding : findings) {
            if (text.length() > 0) {
                text.append("\n\n");
            }
            text.append('[')
                    .append(finding.getRuleCode())
                    .append("] ")
                    .append(finding.getExplanation());
        }
        return text.append("\n\n").append(breakdown.describe()).toString();
    }

    private AlertTriggeredRule toTriggeredRule(RuleFinding finding) {
        return AlertTriggeredRule.builder()
                .ruleCode(finding.getRuleCode())
                .ruleName(finding.getRuleName())
                .ruleVersion(finding.getRuleVersion())
                .typology(finding.getTypology())
                .ruleWeight(finding.getRuleWeight())
                .contributedScore(finding.contributedScore())
                .details(finding.getDetailsJson())
                .triggeredAt(finding.getDetectedAt())
                .build();
    }

    private AlertEvidence toEvidence(RuleFinding.EvidenceItem item) {
        Transaction transaction = item.transaction();
        return AlertEvidence.builder()
                .transaction(transaction)
                .externalTxnId(transaction.getExternalTxnId())
                .evidenceRole(item.role())
                .baseAmount(transaction.getBaseAmount())
                .occurredAt(transaction.getTransactionTimestamp())
                .note(item.note())
                .build();
    }

    /**
     * The alert's headline value is the largest of its findings' totals, not their sum: several
     * rules describing the same money would otherwise multiply the reported exposure.
     */
    private BigDecimal totalAmountOf(List<RuleFinding> findings) {
        BigDecimal total = BigDecimal.ZERO;
        for (RuleFinding finding : findings) {
            if (finding.getTotalAmount() != null) {
                total = total.max(finding.getTotalAmount());
            }
        }
        return total;
    }
}
