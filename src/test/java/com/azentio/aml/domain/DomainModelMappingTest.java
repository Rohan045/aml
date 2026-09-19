package com.azentio.aml.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.azentio.aml.config.JpaAuditingConfig;
import com.azentio.aml.domain.enums.AccountStatus;
import com.azentio.aml.domain.enums.AccountType;
import com.azentio.aml.domain.enums.AlertSeverity;
import com.azentio.aml.domain.enums.AlertStatus;
import com.azentio.aml.domain.enums.AmlTypology;
import com.azentio.aml.domain.enums.Channel;
import com.azentio.aml.domain.enums.KycStatus;
import com.azentio.aml.domain.enums.RiskRating;
import com.azentio.aml.domain.enums.TransactionDirection;
import com.azentio.aml.domain.enums.TransactionType;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/** Verifies the customer -> account -> transaction -> alert model maps and persists correctly. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfig.class)
@ActiveProfiles("test")
class DomainModelMappingTest {

    @Autowired private EntityManager entityManager;

    @Test
    void persistsCustomerAccountTransactionAndAlertGraph() {
        Customer customer = entityManager.merge(sampleCustomer("CUST_00001"));
        Account account = entityManager.merge(sampleAccount("ACC_000001", customer));
        Transaction transaction = entityManager.merge(sampleTransaction("TXN_1", account, customer));

        Alert alert = sampleAlert(customer, account, "ALT-2026-000001");
        alert.addTriggeredRule(
                AlertTriggeredRule.builder()
                        .ruleCode("RULE_CTR_THRESHOLD")
                        .ruleName("Single transaction at or above reporting threshold")
                        .typology(AmlTypology.THRESHOLD_BREACH)
                        .ruleWeight(40)
                        .contributedScore(40)
                        .triggeredAt(Instant.now())
                        .build());
        alert.addEvidence(
                AlertEvidence.builder()
                        .transaction(transaction)
                        .externalTxnId(transaction.getExternalTxnId())
                        .evidenceRole("THRESHOLD_TXN")
                        .baseAmount(transaction.getBaseAmount())
                        .occurredAt(transaction.getTransactionTimestamp())
                        .build());
        Alert saved = entityManager.merge(alert);
        entityManager.flush();
        entityManager.clear();

        Alert reloaded = entityManager.find(Alert.class, saved.getId());
        assertThat(reloaded.getAlertReference()).isEqualTo("ALT-2026-000001");
        assertThat(reloaded.getSeverity()).isEqualTo(AlertSeverity.CRITICAL);
        assertThat(reloaded.getTriggeredRules()).hasSize(1);
        assertThat(reloaded.getEvidence()).hasSize(1);
        assertThat(reloaded.getEvidence().get(0).getExternalTxnId()).isEqualTo("TXN_1");
        assertThat(reloaded.getCustomer().getCustomerId()).isEqualTo("CUST_00001");
        assertThat(reloaded.getCreatedAt()).isNotNull();
        assertThat(reloaded.getCreatedBy()).isEqualTo("SYSTEM");
    }

    @Test
    void dedupeKeyIsUniqueSoConcurrentDetectionCannotDuplicateAnAlert() {
        Customer customer = entityManager.merge(sampleCustomer("CUST_00002"));
        Account account = entityManager.merge(sampleAccount("ACC_000002", customer));
        entityManager.merge(sampleAlert(customer, account, "ALT-2026-000010"));
        entityManager.flush();

        Alert duplicate = sampleAlert(customer, account, "ALT-2026-000011");

        Assertions.assertThrows(
                ConstraintViolationException.class,
                () -> {
                    entityManager.merge(duplicate);
                    entityManager.flush();
                });
    }

    @Test
    void severityBandIsDerivedFromRiskScore() {
        Alert alert = new Alert();
        alert.applyRiskScore(35);
        assertThat(alert.getSeverity()).isEqualTo(AlertSeverity.LOW);
        alert.applyRiskScore(65);
        assertThat(alert.getSeverity()).isEqualTo(AlertSeverity.HIGH);
        alert.applyRiskScore(140);
        assertThat(alert.getRiskScore()).isEqualTo(100);
        assertThat(alert.getSeverity()).isEqualTo(AlertSeverity.CRITICAL);
    }

    private Customer sampleCustomer(String id) {
        return Customer.builder()
                .customerId(id)
                .firstName("Krishna")
                .lastName("Sharma")
                .dateOfBirth(LocalDate.of(1985, 5, 26))
                .age(41)
                .email("krishna.sharma31@example.test")
                .phoneNumber("+91-6939042955")
                .city("Gurugram")
                .state("Haryana")
                .country("IN")
                .postalCode("741262")
                .annualIncome(new BigDecimal("355047.00"))
                .customerSince(LocalDate.of(2024, 11, 26))
                .kycStatus(KycStatus.VERIFIED)
                .riskRating(RiskRating.MEDIUM)
                .politicallyExposed(false)
                .preferredChannel(Channel.PHONE_BANKING)
                .emailVerified(true)
                .phoneVerified(true)
                .complaintsLastYear(1)
                .build();
    }

    private Account sampleAccount(String id, Customer customer) {
        return Account.builder()
                .accountId(id)
                .customer(customer)
                .accountType(AccountType.SAVINGS)
                .accountStatus(AccountStatus.ACTIVE)
                .currency("INR")
                .openDate(LocalDate.of(2019, 3, 31))
                .riskRating(RiskRating.MEDIUM)
                .branchCode("BR119")
                .branchCity("Gurugram")
                .currentBalance(new BigDecimal("25171.02"))
                .build();
    }

    private Transaction sampleTransaction(String externalId, Account account, Customer customer) {
        return Transaction.builder()
                .externalTxnId(externalId)
                .account(account)
                .customer(customer)
                .direction(TransactionDirection.CREDIT)
                .transactionType(TransactionType.CASH_DEPOSIT)
                .channel(Channel.CASH_COUNTER)
                .amount(new BigDecimal("950000.00"))
                .currency("INR")
                .baseAmount(new BigDecimal("950000.00"))
                .baseCurrency("INR")
                .transactionTimestamp(Instant.parse("2026-07-20T10:15:30Z"))
                .valueDate(LocalDate.of(2026, 7, 20))
                .build();
    }

    private Alert sampleAlert(Customer customer, Account account, String reference) {
        Alert alert =
                Alert.builder()
                        .alertReference(reference)
                        .customer(customer)
                        .account(account)
                        .typology(AmlTypology.THRESHOLD_BREACH)
                        .status(AlertStatus.NEW)
                        .title("Cash deposit above reporting threshold")
                        .explanation(
                                "A cash deposit of INR 950,000.00 exceeded the INR 10,000 equivalent "
                                        + "CTR reporting threshold.")
                        .dedupeKey("CUST_00001|THRESHOLD_BREACH|2026-07-20")
                        .firstDetectedAt(Instant.parse("2026-07-20T10:15:31Z"))
                        .lastDetectedAt(Instant.parse("2026-07-20T10:15:31Z"))
                        .totalAmount(new BigDecimal("950000.00"))
                        .transactionCount(1)
                        .build();
        alert.applyRiskScore(85);
        return alert;
    }
}
