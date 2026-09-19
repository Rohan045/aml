package com.azentio.aml.detection;

import com.azentio.aml.domain.Account;
import com.azentio.aml.domain.Customer;
import com.azentio.aml.domain.RuleConfig;
import com.azentio.aml.domain.Transaction;
import com.azentio.aml.domain.WatchlistEntry;
import com.azentio.aml.domain.enums.AccountStatus;
import com.azentio.aml.domain.enums.AccountType;
import com.azentio.aml.domain.enums.AlertSeverity;
import com.azentio.aml.domain.enums.AmlTypology;
import com.azentio.aml.domain.enums.Channel;
import com.azentio.aml.domain.enums.CustomerSegment;
import com.azentio.aml.domain.enums.KycStatus;
import com.azentio.aml.domain.enums.RiskRating;
import com.azentio.aml.domain.enums.TransactionDirection;
import com.azentio.aml.domain.enums.TransactionStatus;
import com.azentio.aml.domain.enums.TransactionType;
import com.azentio.aml.domain.enums.WatchlistSubjectType;
import com.azentio.aml.domain.enums.WatchlistType;
import com.azentio.aml.service.WatchlistService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

public final class TestFixtures {

    private TestFixtures() {}

    public static final Instant BASE_TIME = Instant.parse("2026-09-19T00:00:00Z");

    public static RuleConfig config(String ruleCode, AmlTypology typology) {
        return RuleConfig.builder()
                .ruleCode(ruleCode)
                .ruleName(ruleCode + " name")
                .typology(typology)
                .severity(AlertSeverity.HIGH)
                .riskWeight(20)
                .configVersion(3)
                .dedupeWindowHours(24)
                .build();
    }

    public static RuleConfig config(String ruleCode, AmlTypology typology, java.util.function.Consumer<RuleConfig> customizer) {
        RuleConfig config = config(ruleCode, typology);
        customizer.accept(config);
        return config;
    }

    public static DetectionContext context(Instant start, Instant end) {
        return new DetectionContext(start, end, BASE_TIME.plusSeconds(86_400), emptyWatchlist(), null);
    }

    public static DetectionContext context(Instant start, Instant end, WatchlistService.Snapshot watchlist) {
        return new DetectionContext(start, end, BASE_TIME.plusSeconds(86_400), watchlist, null);
    }

    public static WatchlistService.Snapshot emptyWatchlist() {
        return new WatchlistService.Snapshot(Map.of(), Map.of());
    }

    public static Customer customer(String customerId) {
        return Customer.builder()
                .customerId(customerId)
                .firstName("Test")
                .lastName("Customer")
                .customerSegment(CustomerSegment.RETAIL)
                .kycStatus(KycStatus.VERIFIED)
                .riskRating(RiskRating.LOW)
                .politicallyExposed(false)
                .customerSince(LocalDate.parse("2020-01-01"))
                .build();
    }

    public static Account account(String accountId, Customer customer) {
        return account(accountId, customer, AccountStatus.ACTIVE);
    }

    public static Account account(String accountId, Customer customer, AccountStatus status) {
        return Account.builder()
                .accountId(accountId)
                .customer(customer)
                .accountType(AccountType.SAVINGS)
                .accountStatus(status)
                .currency("USD")
                .openDate(LocalDate.parse("2020-01-01"))
                .riskRating(customer.getRiskRating())
                .build();
    }

    public static Transaction txn(String id, String accountId, String customerId, BigDecimal amount, Instant at) {
        return txn(id, account(accountId, customer(customerId)), amount, at, TransactionDirection.CREDIT, TransactionType.CASH_DEPOSIT);
    }

    public static Transaction txn(String id, Account account, BigDecimal amount, Instant at, TransactionDirection direction, TransactionType type) {
        return Transaction.builder()
                .externalTxnId(id)
                .account(account)
                .customer(account.getCustomer())
                .direction(direction)
                .transactionType(type)
                .channel(Channel.BRANCH)
                .amount(amount)
                .currency("USD")
                .exchangeRate(BigDecimal.ONE)
                .baseAmount(amount)
                .baseCurrency("USD")
                .transactionTimestamp(at)
                .status(TransactionStatus.POSTED)
                .counterpartyName("Ordinary Trading LLC")
                .counterpartyAccount("CP-001")
                .counterpartyBank("Ordinary Bank")
                .counterpartyCountry("US")
                .originCountry("US")
                .destinationCountry("US")
                .crossBorder(false)
                .build();
    }

    public static WatchlistEntry watchlistEntry(WatchlistSubjectType subjectType, WatchlistType listType, String value, String displayName, int riskWeight) {
        return WatchlistEntry.builder()
                .subjectType(subjectType)
                .listType(listType)
                .entryValue(value)
                .normalizedValue(WatchlistService.normalize(value))
                .displayName(displayName)
                .riskWeight(riskWeight)
                .source("Unit test list")
                .active(true)
                .build();
    }

    public static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
