package com.azentio.aml.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.azentio.aml.config.JpaAuditingConfig;
import com.azentio.aml.domain.Account;
import com.azentio.aml.domain.Alert;
import com.azentio.aml.domain.Customer;
import com.azentio.aml.domain.RuleConfig;
import com.azentio.aml.domain.Transaction;
import com.azentio.aml.domain.enums.AccountStatus;
import com.azentio.aml.domain.enums.AccountType;
import com.azentio.aml.domain.enums.AlertSeverity;
import com.azentio.aml.domain.enums.AlertStatus;
import com.azentio.aml.domain.enums.AmlTypology;
import com.azentio.aml.domain.enums.KycStatus;
import com.azentio.aml.domain.enums.RiskRating;
import com.azentio.aml.domain.enums.TransactionDirection;
import com.azentio.aml.domain.enums.TransactionType;
import com.azentio.aml.repository.projection.AccountActivitySummary;
import com.azentio.aml.repository.projection.CustomerBaseline;
import com.azentio.aml.repository.projection.FlowSummary;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Verifies that each detection query selects exactly the transactions its business rule describes.
 *
 * <p>Boundary conditions are asserted deliberately: a rule that is one cent or one hour wrong
 * either floods analysts with false positives or silently misses the pattern it exists to catch.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfig.class)
@ActiveProfiles("test")
class DetectionQueryTest {

    /** Mid-day UTC so calendar-day bucketing is unambiguous in any session time zone. */
    private static final Instant DAY0 = Instant.parse("2026-03-02T12:00:00Z");

    @Autowired private EntityManager entityManager;
    @Autowired private TransactionRepository transactions;
    @Autowired private AlertRepository alerts;
    @Autowired private RuleConfigRepository ruleConfigs;

    private Customer customerA;
    private Customer customerB;
    private Account accountA;
    private Account accountB;

    @BeforeEach
    void setUp() {
        customerA = entityManager.merge(customer("CUST_A"));
        customerB = entityManager.merge(customer("CUST_B"));
        accountA = entityManager.merge(account("ACC_A", customerA, AccountStatus.ACTIVE));
        accountB = entityManager.merge(account("ACC_B", customerB, AccountStatus.ACTIVE));
    }

    // ------------------------------------------------------------------
    // Rule 1 - CTR threshold
    // ------------------------------------------------------------------

    @Test
    void thresholdBreachIsInclusiveOfTheThresholdAndExcludesTheCentBelow() {
        save(txn("T1", accountA, customerA, credit(), "9999.99", DAY0));
        save(txn("T2", accountA, customerA, credit(), "10000.00", DAY0.plus(1, ChronoUnit.HOURS)));
        save(txn("T3", accountA, customerA, credit(), "25000.00", DAY0.plus(2, ChronoUnit.HOURS)));
        flush();

        List<Transaction> breaches =
                transactions.findThresholdBreaches(
                        DAY0.minus(1, ChronoUnit.DAYS),
                        DAY0.plus(1, ChronoUnit.DAYS),
                        new BigDecimal("10000.00"));

        assertThat(breaches).extracting(Transaction::getExternalTxnId).containsExactly("T2", "T3");
    }

    // ------------------------------------------------------------------
    // Rule 2 - Structuring
    // ------------------------------------------------------------------

    @Test
    void structuringNeedsThreeInBandTransactionsOnOneAccountInsideTheWindow() {
        // Account A: three in-band transactions inside the window - a candidate.
        save(txn("S1", accountA, customerA, credit(), "9200.00", DAY0));
        save(txn("S2", accountA, customerA, credit(), "9500.00", DAY0.plus(3, ChronoUnit.HOURS)));
        save(txn("S3", accountA, customerA, credit(), "9900.00", DAY0.plus(9, ChronoUnit.HOURS)));
        // Same account, in band, but outside the 24-hour window - must not count.
        save(txn("S4", accountA, customerA, credit(), "9300.00", DAY0.plus(40, ChronoUnit.HOURS)));
        // Account A, inside window, but outside the band - must not count.
        save(txn("S5", accountA, customerA, credit(), "8999.99", DAY0.plus(4, ChronoUnit.HOURS)));
        // Account B: only two in-band transactions - below the occurrence threshold.
        save(txn("S6", accountB, customerB, credit(), "9400.00", DAY0));
        save(txn("S7", accountB, customerB, credit(), "9600.00", DAY0.plus(2, ChronoUnit.HOURS)));
        flush();

        List<AccountActivitySummary> candidates =
                transactions.findStructuringCandidates(
                        DAY0,
                        DAY0.plus(24, ChronoUnit.HOURS),
                        new BigDecimal("9000.00"),
                        new BigDecimal("9999.99"),
                        3L);

        assertThat(candidates).hasSize(1);
        AccountActivitySummary candidate = candidates.get(0);
        assertThat(candidate.getAccountId()).isEqualTo("ACC_A");
        assertThat(candidate.getTxnCount()).isEqualTo(3);
        assertThat(candidate.getTotalAmount()).isEqualByComparingTo("28600.00");
        assertThat(candidate.getFirstAt()).isEqualTo(DAY0);
        assertThat(candidate.getLastAt()).isEqualTo(DAY0.plus(9, ChronoUnit.HOURS));

        List<Transaction> evidence =
                transactions.findStructuringEvidence(
                        "ACC_A",
                        DAY0,
                        DAY0.plus(24, ChronoUnit.HOURS),
                        new BigDecimal("9000.00"),
                        new BigDecimal("9999.99"));
        assertThat(evidence)
                .extracting(Transaction::getExternalTxnId)
                .containsExactly("S1", "S2", "S3");
    }

    // ------------------------------------------------------------------
    // Rule 3 - Rapid movement
    // ------------------------------------------------------------------

    @Test
    void flowSummarySeparatesInflowFromOutflowAndReturnsZeroRatherThanNull() {
        save(txn("R1", accountA, customerA, credit(), "50000.00", DAY0));
        save(txn("R2", accountA, customerA, debit(), "30000.00", DAY0.plus(5, ChronoUnit.HOURS)));
        save(txn("R3", accountA, customerA, debit(), "12000.00", DAY0.plus(20, ChronoUnit.HOURS)));
        // Outside the 48-hour window - excluded from both sides.
        save(txn("R4", accountA, customerA, debit(), "9000.00", DAY0.plus(60, ChronoUnit.HOURS)));
        flush();

        FlowSummary flows =
                transactions.summariseFlows("ACC_A", DAY0, DAY0.plus(48, ChronoUnit.HOURS));

        assertThat(flows.getInflow()).isEqualByComparingTo("50000.00");
        assertThat(flows.getOutflow()).isEqualByComparingTo("42000.00");

        // An account with no activity must report zero, so callers can divide safely.
        FlowSummary empty =
                transactions.summariseFlows("ACC_B", DAY0, DAY0.plus(48, ChronoUnit.HOURS));
        assertThat(empty.getInflow()).isEqualByComparingTo("0");
        assertThat(empty.getOutflow()).isEqualByComparingTo("0");

        assertThat(
                        transactions.findAccountsWithSignificantInflow(
                                DAY0, DAY0.plus(48, ChronoUnit.HOURS), new BigDecimal("1000.00")))
                .containsExactly("ACC_A");
    }

    // ------------------------------------------------------------------
    // Rule 4 - High-risk jurisdiction and counterparty
    // ------------------------------------------------------------------

    @Test
    void jurisdictionScreeningMatchesAnyLegOfTheTransaction() {
        Transaction viaCounterparty = txn("J1", accountA, customerA, debit(), "500.00", DAY0);
        viaCounterparty.setCounterpartyCountry("IR");
        Transaction viaDestination =
                txn("J2", accountA, customerA, debit(), "600.00", DAY0.plus(1, ChronoUnit.HOURS));
        viaDestination.setDestinationCountry("KP");
        Transaction viaOrigin =
                txn("J3", accountA, customerA, credit(), "700.00", DAY0.plus(2, ChronoUnit.HOURS));
        viaOrigin.setOriginCountry("MM");
        Transaction clean =
                txn("J4", accountA, customerA, debit(), "800.00", DAY0.plus(3, ChronoUnit.HOURS));
        clean.setCounterpartyCountry("IN");
        save(viaCounterparty);
        save(viaDestination);
        save(viaOrigin);
        save(clean);
        flush();

        List<Transaction> hits =
                transactions.findHighRiskJurisdictionExposure(
                        DAY0, DAY0.plus(1, ChronoUnit.DAYS), Set.of("IR", "KP", "MM"));

        assertThat(hits)
                .extracting(Transaction::getExternalTxnId)
                .containsExactlyInAnyOrder("J1", "J2", "J3");
    }

    @Test
    void counterpartyScreeningMatchesOnTheNormalisedName() {
        Transaction sanctioned = txn("C1", accountA, customerA, debit(), "2500.00", DAY0);
        sanctioned.setCounterpartyName("Volkov Trading LLC");
        Transaction ordinary =
                txn("C2", accountA, customerA, debit(), "2500.00", DAY0.plus(1, ChronoUnit.HOURS));
        ordinary.setCounterpartyName("Local Grocery Store");
        save(sanctioned);
        save(ordinary);
        flush();

        List<Transaction> hits =
                transactions.findHighRiskCounterpartyExposure(
                        DAY0, DAY0.plus(1, ChronoUnit.DAYS), Set.of("VOLKOV TRADING LLC"));

        assertThat(hits).extracting(Transaction::getExternalTxnId).containsExactly("C1");
    }

    // ------------------------------------------------------------------
    // Rule 5 - Behavioural deviation
    // ------------------------------------------------------------------

    @Test
    void baselineAveragesOverActiveDaysOnlySoQuietCustomersAreNotFalselyFlagged() {
        // Day 0: two transactions totalling 3,000.
        save(txn("B1", accountA, customerA, credit(), "1000.00", DAY0));
        save(txn("B2", accountA, customerA, credit(), "2000.00", DAY0.plus(1, ChronoUnit.HOURS)));
        // Day 1: one transaction of 6,000.
        save(txn("B3", accountA, customerA, credit(), "6000.00", DAY0.plus(1, ChronoUnit.DAYS)));
        // Day 2: no activity at all - must not drag the average down.
        // Day 3: one transaction of 3,000.
        save(txn("B4", accountA, customerA, credit(), "3000.00", DAY0.plus(3, ChronoUnit.DAYS)));
        // A different customer's activity must not leak into this baseline.
        save(txn("B5", accountB, customerB, credit(), "99000.00", DAY0));
        flush();

        CustomerBaseline baseline =
                transactions.findCustomerBaseline(
                        "CUST_A", DAY0.minus(1, ChronoUnit.DAYS), DAY0.plus(4, ChronoUnit.DAYS));

        assertThat(baseline.getActiveDays()).isEqualTo(3);
        assertThat(baseline.getAvgDailyValue()).isEqualByComparingTo("4000.00");
        assertThat(baseline.getAvgDailyCount()).isCloseTo(1.333d, within(0.01d));

        assertThat(
                        transactions.findDailyActivity(
                                "CUST_A",
                                DAY0.minus(1, ChronoUnit.DAYS),
                                DAY0.plus(4, ChronoUnit.DAYS)))
                .hasSize(3)
                .extracting(d -> d.getTotalValue().intValue())
                .containsExactly(3000, 6000, 3000);
    }

    @Test
    void activeCustomerSweepReturnsEachCustomerOnce() {
        save(txn("A1", accountA, customerA, credit(), "100.00", DAY0));
        save(txn("A2", accountA, customerA, credit(), "200.00", DAY0.plus(1, ChronoUnit.HOURS)));
        save(txn("A3", accountB, customerB, credit(), "300.00", DAY0));
        flush();

        assertThat(transactions.findActiveCustomerIds(DAY0, DAY0.plus(1, ChronoUnit.DAYS)))
                .containsExactlyInAnyOrder("CUST_A", "CUST_B");
    }

    // ------------------------------------------------------------------
    // Supplementary rules
    // ------------------------------------------------------------------

    @Test
    void roundAmountCandidatesRequireExactMultiplesOfTheConfiguredUnit() {
        save(txn("N1", accountA, customerA, debit(), "1000.00", DAY0));
        save(txn("N2", accountA, customerA, debit(), "2000.00", DAY0.plus(1, ChronoUnit.HOURS)));
        save(txn("N3", accountA, customerA, debit(), "5000.00", DAY0.plus(2, ChronoUnit.HOURS)));
        // Not a multiple of 1,000 - excluded, which is what keeps this rule from firing on
        // ordinary payment activity.
        save(txn("N4", accountA, customerA, debit(), "1500.00", DAY0.plus(3, ChronoUnit.HOURS)));
        flush();

        List<AccountActivitySummary> candidates =
                transactions.findRoundAmountCandidates(
                        DAY0,
                        DAY0.plus(24, ChronoUnit.HOURS),
                        new BigDecimal("1000.00"),
                        new BigDecimal("1000.00"),
                        3L);

        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).getAccountId()).isEqualTo("ACC_A");
        assertThat(candidates.get(0).getTxnCount()).isEqualTo(3);
        assertThat(candidates.get(0).getTotalAmount()).isEqualByComparingTo("8000.00");
    }

    @Test
    void dormantActivityLooksAtTheBankOwnAccountClassification() {
        Account dormant = entityManager.merge(account("ACC_D", customerA, AccountStatus.DORMANT));
        save(txn("D1", dormant, customerA, credit(), "7500.00", DAY0));
        // Below the configured materiality floor - not worth an alert.
        save(txn("D2", dormant, customerA, credit(), "100.00", DAY0.plus(1, ChronoUnit.HOURS)));
        // Same amount on an active account - not a reactivation.
        save(txn("D3", accountA, customerA, credit(), "7500.00", DAY0));
        flush();

        List<Transaction> hits =
                transactions.findDormantAccountActivity(
                        DAY0,
                        DAY0.plus(1, ChronoUnit.DAYS),
                        new BigDecimal("5000.00"),
                        Set.of(AccountStatus.DORMANT, AccountStatus.INACTIVE));

        assertThat(hits).extracting(Transaction::getExternalTxnId).containsExactly("D1");
    }

    // ------------------------------------------------------------------
    // Alert de-duplication contract
    // ------------------------------------------------------------------

    @Test
    void repeatDetectionFoldsIntoTheExistingAlertInsteadOfRaisingANewOne() {
        Alert alert = alerts.saveAndFlush(alert("ALT-1", "CUST_A|STRUCTURING|2026-03-02"));
        Instant later = DAY0.plus(2, ChronoUnit.HOURS);

        int updated = alerts.recordRepeatDetection("CUST_A|STRUCTURING|2026-03-02", later);

        assertThat(updated).isEqualTo(1);
        Alert reloaded = alerts.findById(alert.getId()).orElseThrow();
        assertThat(reloaded.getOccurrenceCount()).isEqualTo(2);
        assertThat(reloaded.getLastDetectedAt()).isEqualTo(later);
    }

    @Test
    void unknownDedupeKeyUpdatesNothingSoTheCallerKnowsToInsert() {
        assertThat(alerts.recordRepeatDetection("CUST_A|STRUCTURING|nope", DAY0)).isZero();
    }

    @Test
    void activeRulesAreOrderedByExecutionOrderAndExcludeDisabledOnes() {
        ruleConfigs.saveAll(
                List.of(rule("R_SECOND", 20, true), rule("R_FIRST", 10, true), rule("R_OFF", 5, false)));
        entityManager.flush();

        assertThat(ruleConfigs.findActiveRules(Instant.now()))
                .extracting(RuleConfig::getRuleCode)
                .containsExactly("R_FIRST", "R_SECOND");
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private void save(Transaction transaction) {
        entityManager.merge(transaction);
    }

    /** Native queries bypass the persistence context, so pending state must reach the database. */
    private void flush() {
        entityManager.flush();
        entityManager.clear();
    }

    private static TransactionDirection credit() {
        return TransactionDirection.CREDIT;
    }

    private static TransactionDirection debit() {
        return TransactionDirection.DEBIT;
    }

    private Customer customer(String id) {
        return Customer.builder()
                .customerId(id)
                .firstName("Test")
                .lastName("Customer")
                .country("IN")
                .kycStatus(KycStatus.VERIFIED)
                .riskRating(RiskRating.LOW)
                .politicallyExposed(false)
                .build();
    }

    private Account account(String id, Customer owner, AccountStatus status) {
        return Account.builder()
                .accountId(id)
                .customer(owner)
                .accountType(AccountType.SAVINGS)
                .accountStatus(status)
                .currency("USD")
                .openDate(LocalDate.of(2020, 1, 1))
                .riskRating(RiskRating.LOW)
                .build();
    }

    private Transaction txn(
            String externalId,
            Account account,
            Customer owner,
            TransactionDirection direction,
            String amount,
            Instant at) {
        return Transaction.builder()
                .externalTxnId(externalId)
                .account(account)
                .customer(owner)
                .direction(direction)
                .transactionType(
                        direction == TransactionDirection.CREDIT
                                ? TransactionType.CASH_DEPOSIT
                                : TransactionType.TRANSFER_OUT)
                .amount(new BigDecimal(amount))
                .currency("USD")
                .baseAmount(new BigDecimal(amount))
                .baseCurrency("USD")
                .transactionTimestamp(at)
                .build();
    }

    private Alert alert(String reference, String dedupeKey) {
        Alert alert =
                Alert.builder()
                        .alertReference(reference)
                        .customer(customerA)
                        .account(accountA)
                        .typology(AmlTypology.STRUCTURING)
                        .status(AlertStatus.NEW)
                        .title("Structuring pattern detected")
                        .explanation("Three transactions just below the reporting threshold.")
                        .dedupeKey(dedupeKey)
                        .firstDetectedAt(DAY0)
                        .lastDetectedAt(DAY0)
                        .build();
        alert.applyRiskScore(70);
        return alert;
    }

    private RuleConfig rule(String code, int order, boolean enabled) {
        return RuleConfig.builder()
                .ruleCode(code)
                .ruleName(code)
                .typology(AmlTypology.STRUCTURING)
                .enabled(enabled)
                .executionOrder(order)
                .severity(AlertSeverity.HIGH)
                .riskWeight(30)
                .build();
    }
}
