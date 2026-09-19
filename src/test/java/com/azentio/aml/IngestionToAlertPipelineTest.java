package com.azentio.aml;

import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.azentio.aml.detection.DetectionEngine;
import com.azentio.aml.domain.ExchangeRate;
import com.azentio.aml.domain.RuleConfig;
import com.azentio.aml.domain.WatchlistEntry;
import com.azentio.aml.domain.enums.AlertSeverity;
import com.azentio.aml.domain.enums.AmlTypology;
import com.azentio.aml.domain.enums.WatchlistSubjectType;
import com.azentio.aml.domain.enums.WatchlistType;
import com.azentio.aml.repository.AccountRepository;
import com.azentio.aml.repository.AlertRepository;
import com.azentio.aml.repository.CustomerRepository;
import com.azentio.aml.repository.ExchangeRateRepository;
import com.azentio.aml.repository.RuleConfigRepository;
import com.azentio.aml.repository.TransactionRepository;
import com.azentio.aml.repository.WatchlistEntryRepository;
import com.azentio.aml.service.AlertService;
import com.azentio.aml.service.WatchlistService;
import com.azentio.aml.service.ingestion.CsvIngestionService;
import com.azentio.aml.service.ingestion.IngestionResult;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Proves the CSV path without depending on generated fixture files or Python at test runtime.
 *
 * <p>The production demo CSVs are deliberately date-relative because behavioural and dormant-account
 * rules age out. This test keeps that property inside Java: every transaction is laid out relative
 * to today's UTC date, while IDs, amounts and counterparties stay fixed. That makes the scenario
 * deterministic for assertions and self-contained for any Maven runner, including offline CI.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(
        properties = {
            "sentinel.detection.scheduled-sweep-enabled=false",
            // A private in-memory database. This class is @DirtiesContext, and tearing the
            // context down runs Hibernate's create-drop teardown against whatever database it
            // is pointed at. Sharing the default `sentinel` instance would therefore empty the
            // schema out from under every test that happens to run afterwards.
            "spring.datasource.url=jdbc:h2:mem:sentinel_pipeline;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
        })
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class IngestionToAlertPipelineTest {

    private static final DateTimeFormatter CSV_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    @Autowired private CsvIngestionService ingestionService;
    @Autowired private DetectionEngine detectionEngine;
    @Autowired private CustomerRepository customers;
    @Autowired private AccountRepository accounts;
    @Autowired private TransactionRepository transactions;
    @Autowired private AlertRepository alerts;
    @Autowired private RuleConfigRepository ruleConfigs;
    @Autowired private ExchangeRateRepository exchangeRates;
    @Autowired private WatchlistEntryRepository watchlistEntries;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void seedReferenceData() {
        seedExchangeRates();
        seedRuleConfigs();
        seedWatchlist();
    }

    @Test
    void csvIngestionNormalisesPersistsScreensAndDeduplicatesAlertsEndToEnd() {
        TestCsv csv = TestCsv.generate(LocalDate.now(ZoneOffset.UTC));

        IngestionResult customerResult = ingestionService.ingestCustomers(file("customers.csv", csv.customers()));
        IngestionResult accountResult = ingestionService.ingestAccounts(file("accounts.csv", csv.accounts()));
        IngestionResult transactionResult = ingestionService.ingestTransactions(file("transactions.csv", csv.transactions()));
        long alertsAfterIngest = alerts.count();
        long occurrenceCountAfterIngest = totalAlertOccurrences();

        IngestionResult duplicateResult = ingestionService.ingestTransactions(file("transactions.csv", csv.transactions()));
        AlertService.Outcome firstReplay = detectionEngine.sweep(csv.windowStart(), csv.windowEnd());
        long alertsAfterFirstReplay = alerts.count();
        long occurrenceCountAfterFirstReplay = totalAlertOccurrences();
        AlertService.Outcome secondReplay = detectionEngine.sweep(csv.windowStart(), csv.windowEnd());

        Map<String, Long> alertsByTypology = alertCountsByTypology();
        EnumSet<AmlTypology> requiredTypologies = EnumSet.of(
                AmlTypology.THRESHOLD_BREACH,
                AmlTypology.STRUCTURING,
                AmlTypology.RAPID_MOVEMENT,
                AmlTypology.HIGH_RISK_JURISDICTION,
                AmlTypology.ROUND_AMOUNT_PATTERN,
                AmlTypology.HIGH_RISK_COUNTERPARTY);

        assertSoftly(softly -> {
            softly.assertThat(customerResult.failed()).as("customer validation failures").isZero();
            softly.assertThat(accountResult.failed()).as("account validation failures").isZero();
            softly.assertThat(transactionResult.failed()).as("transaction validation failures").isZero();
            softly.assertThat(customers.count()).as("customer row count").isEqualTo(csv.customerRows());
            softly.assertThat(accounts.count()).as("account row count").isEqualTo(csv.accountRows());
            softly.assertThat(transactions.count()).as("transaction row count").isEqualTo(csv.transactionRows());
            softly.assertThat(transactionResult.succeeded()).as("transactions stored").isEqualTo(csv.transactionRows());
            softly.assertThat(unNormalisedTransactionCount()).as("transactions missing FX normalisation").isZero();
            softly.assertThat(duplicateResult.succeeded()).as("duplicate replay stores no rows").isZero();
            softly.assertThat(duplicateResult.duplicates()).as("duplicate replay reports every transaction").isEqualTo(csv.transactionRows());
            softly.assertThat(transactions.count()).as("transaction count after duplicate replay").isEqualTo(csv.transactionRows());
            softly.assertThat(alertsAfterIngest).as("alerts raised by ingestion-triggered detection").isPositive();
            softly.assertThat(alertsByTypology.keySet()).as("detected typologies").containsAll(requiredTypologies.stream().map(Enum::name).toList());
            softly.assertThat(alertCountForCustomers("CUST_10001", "CUST_10002", "CUST_10003", "CUST_10004"))
                    .as("clean control customers must remain silent")
                    .isZero();
            softly.assertThat(firstReplay.created()).as("first replay creates no duplicate alerts").isZero();
            softly.assertThat(firstReplay.aggregated()).as("first replay folds repeat detections").isPositive();
            softly.assertThat(alertsAfterFirstReplay).as("alert count after first replay").isEqualTo(alertsAfterIngest);
            softly.assertThat(occurrenceCountAfterFirstReplay).as("first replay increments occurrences")
                    .isGreaterThan(occurrenceCountAfterIngest);
            softly.assertThat(secondReplay.created()).as("second replay creates no duplicate alerts").isZero();
            softly.assertThat(secondReplay.aggregated()).as("second replay folds repeat detections").isPositive();
            softly.assertThat(alerts.count()).as("alert count after second replay").isEqualTo(alertsAfterIngest);
            softly.assertThat(totalAlertOccurrences()).as("second replay increments occurrences again")
                    .isGreaterThan(occurrenceCountAfterFirstReplay);
        });

        // Behavioural deviation and dormant reactivation are also generated relative to today's
        // date, but they are intentionally not part of the minimum typology contract above because
        // their business definitions are sensitive to lookback-window tuning.
    }

    private long unNormalisedTransactionCount() {
        Long count = jdbc.queryForObject(
                "select count(*) from transactions where base_amount is null or base_currency <> 'USD'",
                Long.class);
        return count == null ? 0L : count;
    }

    private long alertCountForCustomers(String... customerIds) {
        String placeholders = String.join(",", java.util.Collections.nCopies(customerIds.length, "?"));
        Long count = jdbc.queryForObject(
                "select count(*) from alerts where customer_id in (" + placeholders + ")",
                Long.class,
                (Object[]) customerIds);
        return count == null ? 0L : count;
    }

    private long totalAlertOccurrences() {
        Long count = jdbc.queryForObject("select coalesce(sum(occurrence_count), 0) from alerts", Long.class);
        return count == null ? 0L : count;
    }

    private Map<String, Long> alertCountsByTypology() {
        return jdbc.query(
                "select typology, count(*) from alerts group by typology",
                rs -> {
                    Map<String, Long> counts = new HashMap<>();
                    while (rs.next()) {
                        counts.put(rs.getString(1), rs.getLong(2));
                    }
                    return counts;
                });
    }

    private MockMultipartFile file(String name, String csv) {
        return new MockMultipartFile("file", name, "text/csv", csv.getBytes(StandardCharsets.UTF_8));
    }

    private void seedExchangeRates() {
        if (exchangeRates.count() > 0) {
            return;
        }
        LocalDate effective = LocalDate.parse("2024-01-01");
        exchangeRates.saveAll(List.of(
                rate("USD", "1.00000000", effective), rate("INR", "0.01200000", effective),
                rate("EUR", "1.08000000", effective), rate("GBP", "1.27000000", effective),
                rate("AED", "0.27230000", effective), rate("SGD", "0.74000000", effective),
                rate("CHF", "1.13000000", effective), rate("HKD", "0.12800000", effective),
                rate("JPY", "0.00640000", effective), rate("AUD", "0.65000000", effective),
                rate("CAD", "0.73000000", effective), rate("RUB", "0.01100000", effective)));
    }

    private ExchangeRate rate(String fromCurrency, String rate, LocalDate effectiveFrom) {
        return ExchangeRate.builder()
                .fromCurrency(fromCurrency)
                .toCurrency("USD")
                .rate(new BigDecimal(rate))
                .effectiveFrom(effectiveFrom)
                .source("TEST-SEED")
                .build();
    }

    private void seedRuleConfigs() {
        if (ruleConfigs.count() > 0) {
            return;
        }
        Instant effective = Instant.now().minusSeconds(86_400);
        ruleConfigs.saveAll(List.of(
                rule("CTR_THRESHOLD_10K", "Currency Transaction Report Threshold", AmlTypology.THRESHOLD_BREACH,
                        10, AlertSeverity.HIGH, 30, "10000.00", null, null, null, null, null, null, 24,
                        "{\"inclusive\":true,\"appliesTo\":[\"CASH_DEPOSIT\",\"CASH_WITHDRAWAL\",\"WIRE_IN\",\"WIRE_OUT\",\"TRANSFER_IN\",\"TRANSFER_OUT\"]}", effective),
                rule("STRUCTURING_24H", "Structuring Below Reporting Threshold", AmlTypology.STRUCTURING,
                        20, AlertSeverity.HIGH, 35, "9999.99", "9000.00", 3, 24, null, null, null, 24,
                        "{\"scope\":\"ACCOUNT\",\"windowType\":\"ROLLING\",\"boundsInclusive\":true}", effective),
                rule("RAPID_MOVEMENT_48H", "Rapid Movement of Funds", AmlTypology.RAPID_MOVEMENT,
                        30, AlertSeverity.HIGH, 30, "1000.00", null, null, 48, null, null, "0.8000", 48,
                        "{\"scope\":\"ACCOUNT\",\"minimumInflowUsd\":1000,\"outflowTypes\":[\"TRANSFER_OUT\",\"WIRE_OUT\",\"CASH_WITHDRAWAL\"]}", effective),
                rule("HIGH_RISK_JURISDICTION", "High-Risk Jurisdiction Exposure", AmlTypology.HIGH_RISK_JURISDICTION,
                        40, AlertSeverity.CRITICAL, 40, null, null, null, null, null, null, null, 24,
                        "{\"matchFields\":[\"counterpartyCountry\",\"originCountry\",\"destinationCountry\"],\"listTypes\":[\"SANCTIONS\",\"FATF_BLACKLIST\",\"FATF_GREYLIST\"]}", effective),
                rule("BEHAVIOURAL_DEVIATION_3X", "Behavioural Deviation From Baseline", AmlTypology.BEHAVIOURAL_DEVIATION,
                        50, AlertSeverity.MEDIUM, 25, null, null, null, 24, "3.00", 90, null, 24,
                        "{\"scope\":\"CUSTOMER\",\"metrics\":[\"VALUE\",\"COUNT\"],\"minimumBaselineDays\":14,\"minimumDailyValueUsd\":500}", effective),
                rule("ROUND_AMOUNT_PATTERN", "Repeated Round-Number Amounts", AmlTypology.ROUND_AMOUNT_PATTERN,
                        60, AlertSeverity.MEDIUM, 15, "1000.00", null, 3, 24, null, null, null, 24,
                        "{\"scope\":\"ACCOUNT\",\"roundingMultiple\":1000,\"minimumAmountUsd\":1000}", effective),
                rule("HIGH_RISK_COUNTERPARTY", "High-Risk Counterparty", AmlTypology.HIGH_RISK_COUNTERPARTY,
                        70, AlertSeverity.HIGH, 30, null, null, null, null, null, null, null, 24,
                        "{\"matchFields\":[\"counterpartyName\",\"counterpartyBank\",\"counterpartyAccount\"],\"listTypes\":[\"SANCTIONS\",\"ADVERSE_MEDIA\",\"INTERNAL_HIGH_RISK\"]}", effective),
                rule("DORMANT_REACTIVATION", "Dormant Account Reactivation", AmlTypology.DORMANT_ACCOUNT_REACTIVATION,
                        80, AlertSeverity.MEDIUM, 20, "5000.00", null, null, null, null, 180, null, 24,
                        "{\"scope\":\"ACCOUNT\",\"dormancyDays\":180,\"applicableStatuses\":[\"DORMANT\",\"INACTIVE\"]}", effective)));
    }

    private RuleConfig rule(
            String code, String name, AmlTypology typology, int order, AlertSeverity severity, int riskWeight,
            String threshold, String minThreshold, Integer occurrences, Integer hours, String deviation,
            Integer lookbackDays, String ratio, int dedupeHours, String parameters, Instant effectiveFrom) {
        return RuleConfig.builder()
                .ruleCode(code)
                .ruleName(name)
                .description(name)
                .typology(typology)
                .enabled(true)
                .executionOrder(order)
                .severity(severity)
                .riskWeight(riskWeight)
                .thresholdAmount(threshold == null ? null : new BigDecimal(threshold))
                .thresholdAmountMin(minThreshold == null ? null : new BigDecimal(minThreshold))
                .thresholdCurrency("USD")
                .minOccurrences(occurrences)
                .timeWindowHours(hours)
                .deviationMultiplier(deviation == null ? null : new BigDecimal(deviation))
                .lookbackDays(lookbackDays)
                .ratioThreshold(ratio == null ? null : new BigDecimal(ratio))
                .dedupeWindowHours(dedupeHours)
                .parameters(parameters)
                .configVersion(1)
                .effectiveFrom(effectiveFrom)
                .build();
    }

    private void seedWatchlist() {
        if (watchlistEntries.count() > 0) {
            return;
        }
        Instant effective = Instant.now().minusSeconds(86_400);
        watchlistEntries.saveAll(List.of(
                entry(WatchlistSubjectType.COUNTRY, WatchlistType.FATF_BLACKLIST, "KP", "Korea Democratic People's Republic of", 100, "FATF", effective),
                entry(WatchlistSubjectType.COUNTRY, WatchlistType.FATF_BLACKLIST, "IR", "Iran Islamic Republic of", 100, "FATF", effective),
                entry(WatchlistSubjectType.COUNTRY, WatchlistType.FATF_BLACKLIST, "MM", "Myanmar", 90, "FATF", effective),
                entry(WatchlistSubjectType.COUNTRY, WatchlistType.FATF_GREYLIST, "SY", "Syrian Arab Republic", 70, "FATF", effective),
                entry(WatchlistSubjectType.COUNTRY, WatchlistType.FATF_GREYLIST, "YE", "Yemen", 70, "FATF", effective),
                entry(WatchlistSubjectType.COUNTRY, WatchlistType.FATF_GREYLIST, "SS", "South Sudan", 65, "FATF", effective),
                entry(WatchlistSubjectType.COUNTRY, WatchlistType.FATF_GREYLIST, "HT", "Haiti", 65, "FATF", effective),
                entry(WatchlistSubjectType.COUNTRY, WatchlistType.FATF_GREYLIST, "ML", "Mali", 60, "FATF", effective),
                entry(WatchlistSubjectType.COUNTRY, WatchlistType.FATF_GREYLIST, "BF", "Burkina Faso", 60, "FATF", effective),
                entry(WatchlistSubjectType.COUNTRY, WatchlistType.FATF_GREYLIST, "CD", "Congo Democratic Republic", 60, "FATF", effective),
                entry(WatchlistSubjectType.COUNTRY, WatchlistType.FATF_GREYLIST, "MZ", "Mozambique", 55, "FATF", effective),
                entry(WatchlistSubjectType.COUNTRY, WatchlistType.TAX_HAVEN, "KY", "Cayman Islands", 40, "INTERNAL", effective),
                entry(WatchlistSubjectType.COUNTRY, WatchlistType.TAX_HAVEN, "VG", "Virgin Islands British", 40, "INTERNAL", effective),
                entry(WatchlistSubjectType.COUNTRY, WatchlistType.TAX_HAVEN, "PA", "Panama", 35, "INTERNAL", effective),
                entry(WatchlistSubjectType.COUNTRY, WatchlistType.TAX_HAVEN, "SC", "Seychelles", 35, "INTERNAL", effective),
                entry(WatchlistSubjectType.COUNTRY, WatchlistType.TAX_HAVEN, "BZ", "Belize", 35, "INTERNAL", effective),
                entry(WatchlistSubjectType.COUNTRY, WatchlistType.TAX_HAVEN, "VU", "Vanuatu", 30, "INTERNAL", effective),
                entry(WatchlistSubjectType.COUNTRY, WatchlistType.TAX_HAVEN, "MH", "Marshall Islands", 30, "INTERNAL", effective),
                entry(WatchlistSubjectType.COUNTERPARTY, WatchlistType.SANCTIONS, "Volkov Trading LLC", "Volkov Trading LLC", 95, "DEMO", effective),
                entry(WatchlistSubjectType.COUNTERPARTY, WatchlistType.SANCTIONS, "Zarand Petrochem FZE", "Zarand Petrochem FZE", 95, "DEMO", effective),
                entry(WatchlistSubjectType.COUNTERPARTY, WatchlistType.ADVERSE_MEDIA, "Apex Crypto Exchange", "Apex Crypto Exchange", 60, "DEMO", effective),
                entry(WatchlistSubjectType.COUNTERPARTY, WatchlistType.INTERNAL_HIGH_RISK, "Golden Sands Exchange", "Golden Sands Exchange", 55, "DEMO", effective),
                entry(WatchlistSubjectType.BANK, WatchlistType.SANCTIONS, "Bank Mellat", "Bank Mellat", 90, "DEMO", effective),
                entry(WatchlistSubjectType.BANK, WatchlistType.INTERNAL_HIGH_RISK, "Northern Star Bank", "Northern Star Bank", 50, "DEMO", effective)));
    }

    private WatchlistEntry entry(
            WatchlistSubjectType subjectType, WatchlistType listType, String value, String displayName,
            int riskWeight, String source, Instant effectiveFrom) {
        return WatchlistEntry.builder()
                .subjectType(subjectType)
                .listType(listType)
                .entryValue(value)
                .normalizedValue(WatchlistService.normalize(value))
                .displayName(displayName)
                .riskWeight(riskWeight)
                .source(source)
                .active(true)
                .effectiveFrom(effectiveFrom)
                .notes("Test seed mirroring V2 reference data")
                .build();
    }

    private record TestCsv(
            String customers, String accounts, String transactions, int customerRows, int accountRows,
            int transactionRows, Instant windowStart, Instant windowEnd) {

        static TestCsv generate(LocalDate anchor) {
            Builder builder = new Builder(anchor);
            builder.generate();
            return builder.build();
        }
    }

    private static final class Builder {
        private final LocalDate anchor;
        private final List<String[]> customerRows = new ArrayList<>();
        private final List<String[]> accountRows = new ArrayList<>();
        private final List<String[]> transactionRows = new ArrayList<>();
        private final AtomicInteger txnSequence = new AtomicInteger();

        private Builder(LocalDate anchor) {
            this.anchor = anchor;
        }

        private void generate() {
            for (int n = 1; n <= 4; n++) {
                String cid = customer("CUST_1000" + n, "Clean", "Control" + n, "LOW", "RETAIL", "N");
                String aid = account("ACC_1000" + n, cid, "ACTIVE", "SAVINGS", "LOW");
                txn(aid, cid, 12, 9, "1200.00", "TRANSFER_IN", "INTERNET_BANKING", "Payroll", "Ordinary Employer", "Ordinary Bank", "IN", "IN", "IN");
                txn(aid, cid, 4, 11, "9850.00", "CHEQUE_DEPOSIT", "BRANCH", "Legitimate sale", "Local Buyer", "Ordinary Bank", "IN", "IN", "IN");
            }

            String thresholdCustomer = customer("CUST_20001", "Rohit", "Mehra", "MEDIUM", "PREMIUM", "N");
            String thresholdAccount = account("ACC_20001", thresholdCustomer, "ACTIVE", "CURRENT", "MEDIUM");
            txn(thresholdAccount, thresholdCustomer, 2, 11, "12500.00", "CASH_DEPOSIT", "CASH_COUNTER", "Cash deposit", "", "", "IN", "IN", "IN");

            String structuringCustomer = customer("CUST_20002", "Sunil", "Kapoor", "MEDIUM", "RETAIL", "N");
            String structuringAccount = account("ACC_20002", structuringCustomer, "ACTIVE", "CURRENT", "MEDIUM");
            txn(structuringAccount, structuringCustomer, 3, 9, "9400.00", "CASH_DEPOSIT", "CASH_COUNTER", "Cash deposit", "", "", "IN", "IN", "IN");
            txn(structuringAccount, structuringCustomer, 3, 12, "9750.00", "CASH_DEPOSIT", "CASH_COUNTER", "Cash deposit", "", "", "IN", "IN", "IN");
            txn(structuringAccount, structuringCustomer, 3, 16, "9200.00", "CASH_DEPOSIT", "CASH_COUNTER", "Cash deposit", "", "", "IN", "IN", "IN");

            String rapidCustomer = customer("CUST_20003", "Farhan", "Sheikh", "MEDIUM", "SME", "N");
            String rapidAccount = account("ACC_20003", rapidCustomer, "ACTIVE", "CURRENT", "MEDIUM");
            txn(rapidAccount, rapidCustomer, 5, 10, "60000.00", "WIRE_IN", "SWIFT", "Inward remittance", "Meridian Holdings Ltd", "Northern Star Bank", "AE", "AE", "IN");
            txn(rapidAccount, rapidCustomer, 5, 14, "24000.00", "TRANSFER_OUT", "RTGS", "Outward transfer", "Larkspur Ventures", "Ordinary Bank", "IN", "IN", "IN");
            txn(rapidAccount, rapidCustomer, 4, 5, "19500.00", "TRANSFER_OUT", "RTGS", "Outward transfer", "Cobalt Trade Partners", "Ordinary Bank", "IN", "IN", "IN");
            txn(rapidAccount, rapidCustomer, 4, 14, "12000.00", "TRANSFER_OUT", "RTGS", "Outward transfer", "Westbridge Logistics", "Ordinary Bank", "IN", "IN", "IN");

            String jurisdictionCustomer = customer("CUST_20004", "Imran", "Qureshi", "HIGH", "SME", "N");
            String jurisdictionAccount = account("ACC_20004", jurisdictionCustomer, "ACTIVE", "CURRENT", "HIGH");
            txn(jurisdictionAccount, jurisdictionCustomer, 6, 14, "7400.00", "WIRE_OUT", "SWIFT", "Machinery settlement", "Zarand Petrochem FZE", "Bank Mellat", "IR", "IN", "IR");

            String behaviourCustomer = customer("CUST_20005", "Anita", "Deshpande", "LOW", "RETAIL", "N");
            String behaviourAccount = account("ACC_20005", behaviourCustomer, "ACTIVE", "SAVINGS", "LOW");
            for (int d = 90; d >= 10; d -= 5) {
                txn(behaviourAccount, behaviourCustomer, d, 11, "150.00", "CARD_PAYMENT", "CARD_POS", "Everyday spend", "Local Store", "", "IN", "IN", "IN");
            }
            txn(behaviourAccount, behaviourCustomer, 1, 9, "1900.00", "TRANSFER_OUT", "IMPS", "Spike transfer", "Harbour Point Traders", "Ordinary Bank", "IN", "IN", "IN");
            txn(behaviourAccount, behaviourCustomer, 1, 11, "2250.00", "TRANSFER_OUT", "IMPS", "Spike transfer", "Harbour Point Traders", "Ordinary Bank", "IN", "IN", "IN");
            txn(behaviourAccount, behaviourCustomer, 1, 14, "1750.00", "TRANSFER_OUT", "IMPS", "Spike transfer", "Harbour Point Traders", "Ordinary Bank", "IN", "IN", "IN");

            String roundCustomer = customer("CUST_20006", "Vikram", "Nair", "MEDIUM", "SME", "N");
            String roundAccount = account("ACC_20006", roundCustomer, "ACTIVE", "CURRENT", "MEDIUM");
            txn(roundAccount, roundCustomer, 4, 9, "5000.00", "TRANSFER_OUT", "NEFT", "Settlement", "Pinnacle Works", "Ordinary Bank", "IN", "IN", "IN");
            txn(roundAccount, roundCustomer, 4, 12, "3000.00", "TRANSFER_OUT", "NEFT", "Settlement", "Pinnacle Works", "Ordinary Bank", "IN", "IN", "IN");
            txn(roundAccount, roundCustomer, 4, 15, "2000.00", "TRANSFER_OUT", "NEFT", "Settlement", "Pinnacle Works", "Ordinary Bank", "IN", "IN", "IN");

            String counterpartyCustomer = customer("CUST_20007", "Priya", "Chandran", "HIGH", "HNI", "Y");
            String counterpartyAccount = account("ACC_20007", counterpartyCustomer, "ACTIVE", "CURRENT", "HIGH");
            txn(counterpartyAccount, counterpartyCustomer, 3, 15, "6800.00", "TRANSFER_OUT", "RTGS", "Consultancy retainer", "Volkov Trading LLC", "Northern Star Bank", "IN", "IN", "IN");

            String dormantCustomer = customer("CUST_20008", "Sanjay", "Bhatt", "LOW", "RETAIL", "N");
            String dormantAccount = account("ACC_20008", dormantCustomer, "DORMANT", "SAVINGS", "LOW");
            txn(dormantAccount, dormantCustomer, 8, 12, "24000.00", "TRANSFER_IN", "IMPS", "Dormant inflow", "Silverline Exports", "Ordinary Bank", "IN", "IN", "IN");
        }

        private TestCsv build() {
            String customers = csv(customerHeader(), customerRows);
            String accounts = csv(accountHeader(), accountRows);
            String transactions = csv(transactionHeader(), transactionRows);
            Instant start = transactionRows.stream().map(row -> LocalDateTime.parse(row[8], CSV_TIMESTAMP).toInstant(ZoneOffset.UTC)).min(Instant::compareTo).orElseThrow();
            Instant end = transactionRows.stream().map(row -> LocalDateTime.parse(row[8], CSV_TIMESTAMP).toInstant(ZoneOffset.UTC)).max(Instant::compareTo).orElseThrow().plusSeconds(1);
            return new TestCsv(customers, accounts, transactions, customerRows.size(), accountRows.size(), transactionRows.size(), start, end);
        }

        private String customer(String cid, String first, String last, String risk, String segment, String pep) {
            customerRows.add(new String[] {cid, first, last, "M", "1985-04-12", "40", first.toLowerCase() + "." + last.toLowerCase() + "@example.com", "+919800000000", "NID" + cid.substring(5), "Mumbai", "Maharashtra", "IN", "400001", "Salaried", "850000.00", "MARRIED", "GRADUATE", "EMPLOYED", "2019-06-01", segment, "VERIFIED", risk, pep, "MOBILE_APP", "Y", "Y", "0"});
            return cid;
        }

        private String account(String aid, String cid, String status, String type, String risk) {
            accountRows.add(new String[] {aid, cid, type, status, "USD", "2019-06-05", "", risk, "BR001", "Mumbai", "24500.00", "21000.00", "0.00", "0.00", "N", "CLASSIC", "N", "2", "Y", "", "22", "SILVER"});
            return aid;
        }

        private void txn(String aid, String cid, int daysBack, int hour, String amount, String type, String channel,
                String narration, String counterparty, String bank, String counterpartyCountry, String origin, String destination) {
            LocalDateTime at = anchor.minusDays(daysBack).atTime(hour, 0);
            String id = "TXN_" + String.format("%07d", txnSequence.incrementAndGet());
            String direction = switch (type) {
                case "CASH_DEPOSIT", "TRANSFER_IN", "WIRE_IN", "CHEQUE_DEPOSIT", "LOAN_DISBURSEMENT", "INTEREST_CREDIT" -> "CREDIT";
                default -> "DEBIT";
            };
            transactionRows.add(new String[] {id, aid, cid, direction, type, channel, amount, "USD", at.format(CSV_TIMESTAMP), at.toLocalDate().toString(), "POSTED", narration, "", counterparty, "CP" + txnSequence.get(), bank, "", counterpartyCountry, origin, destination, origin.equals(destination) ? "N" : "Y", "", "BR001", "DEV-1", "10.0.0.1"});
        }

        private static String csv(String[] header, List<String[]> rows) {
            StringBuilder out = new StringBuilder(String.join(",", header)).append('\n');
            for (String[] row : rows) {
                out.append(String.join(",", row)).append('\n');
            }
            return out.toString();
        }

        private static String[] customerHeader() {
            return new String[] {"customer_id", "first_name", "last_name", "gender", "date_of_birth", "age", "email", "phone_number", "national_id", "city", "state", "country", "postal_code", "occupation", "annual_income", "marital_status", "education_level", "employment_status", "customer_since", "customer_segment", "kyc_status", "risk_rating", "is_politically_exposed", "preferred_channel", "email_verified", "phone_verified", "num_complaints_last_year"};
        }

        private static String[] accountHeader() {
            return new String[] {"account_id", "customer_id", "account_type", "account_status", "currency", "open_date", "close_date", "risk_rating", "branch_code", "branch_city", "current_balance", "avg_monthly_balance_6m", "credit_limit", "credit_utilization_pct", "overdraft_enabled", "card_type", "is_joint_account", "num_linked_devices", "mobile_banking_enrolled", "last_login_date", "avg_monthly_txn_count", "account_tier"};
        }

        private static String[] transactionHeader() {
            return new String[] {"transaction_id", "account_id", "customer_id", "direction", "transaction_type", "channel", "amount", "currency", "transaction_timestamp", "value_date", "status", "narration", "balance_after", "counterparty_name", "counterparty_account", "counterparty_bank", "counterparty_bank_code", "counterparty_country", "origin_country", "destination_country", "is_cross_border", "merchant_category_code", "branch_code", "device_id", "ip_address"};
        }
    }
}
