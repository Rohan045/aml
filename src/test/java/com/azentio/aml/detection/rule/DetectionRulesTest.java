package com.azentio.aml.detection.rule;

import static com.azentio.aml.detection.TestFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.azentio.aml.detection.DetectionContext;
import com.azentio.aml.detection.RuleFinding;
import com.azentio.aml.domain.Account;
import com.azentio.aml.domain.RuleConfig;
import com.azentio.aml.domain.Transaction;
import com.azentio.aml.domain.WatchlistEntry;
import com.azentio.aml.domain.enums.AccountStatus;
import com.azentio.aml.domain.enums.AmlTypology;
import com.azentio.aml.domain.enums.TransactionDirection;
import com.azentio.aml.domain.enums.TransactionType;
import com.azentio.aml.domain.enums.WatchlistSubjectType;
import com.azentio.aml.domain.enums.WatchlistType;
import com.azentio.aml.repository.TransactionRepository;
import com.azentio.aml.repository.projection.AccountActivitySummary;
import com.azentio.aml.repository.projection.DailyActivity;
import com.azentio.aml.service.WatchlistService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DetectionRulesTest {

    @Mock TransactionRepository repository;

    private final Instant start = BASE_TIME;
    private final Instant end = BASE_TIME.plusSeconds(48 * 3600);

    @Test
    void thresholdBreachFiresForInclusiveTenThousandTransaction() {
        Transaction txn = txn("T-10000", "ACC-1", "CUST-1", bd("10000.00"), start.plusSeconds(60));
        RuleConfig config = config("CTR_THRESHOLD_10K", AmlTypology.THRESHOLD_BREACH);
        when(repository.findThresholdBreaches(start, end, bd("10000.00"))).thenReturn(List.of(txn));

        List<RuleFinding> findings = new ThresholdBreachRule(repository).evaluate(config, context(start, end));

        assertThat(findings).hasSize(1);
        RuleFinding finding = findings.get(0);
        assertCommonFinding(finding, AmlTypology.THRESHOLD_BREACH, "ACC-1", "CUST-1", txn.getTransactionTimestamp(), txn.getTransactionTimestamp(), "T-10000");
        assertThat(evidenceIds(finding)).containsExactly("T-10000");
        assertThat(finding.getExplanation()).contains("reaches or exceeds", "reporting threshold");
    }

    @Test
    void thresholdBreachIgnoresTransactionBelowThresholdReturnedByRepository() {
        Transaction txn = txn("T-9999", "ACC-1", "CUST-1", bd("9999.99"), start.plusSeconds(60));
        when(repository.findThresholdBreaches(any(), any(), any())).thenReturn(List.of(txn));

        List<RuleFinding> findings = new ThresholdBreachRule(repository)
                .evaluate(config("CTR_THRESHOLD_10K", AmlTypology.THRESHOLD_BREACH), context(start, end));

        assertThat(findings).isEmpty();
    }

    @Test
    void structuringFiresForThreeBandTransactionsInsideRolling24Hours() {
        RuleConfig config = config("STRUCTURING_24H", AmlTypology.STRUCTURING);
        List<Transaction> txns = List.of(
                txn("S-1", "ACC-S", "CUST-S", bd("9000.00"), start.plusSeconds(3600)),
                txn("S-2", "ACC-S", "CUST-S", bd("9500.00"), start.plusSeconds(7200)),
                txn("S-3", "ACC-S", "CUST-S", bd("9999.99"), start.plusSeconds(23 * 3600)));
        when(repository.findStructuringCandidates(eq(start), eq(end), eq(bd("9000.00")), eq(bd("9999.99")), eq(3L)))
                .thenReturn(List.of(summary("ACC-S", 3, bd("28499.99"), txns.get(0).getTransactionTimestamp(), txns.get(2).getTransactionTimestamp())));
        when(repository.findStructuringEvidence(eq("ACC-S"), eq(start), eq(end), eq(bd("9000.00")), eq(bd("9999.99"))))
                .thenReturn(txns);

        List<RuleFinding> findings = new StructuringRule(repository).evaluate(config, context(start, end));

        assertThat(findings).hasSize(1);
        RuleFinding finding = findings.get(0);
        assertCommonFinding(finding, AmlTypology.STRUCTURING, "ACC-S", "CUST-S", txns.get(0).getTransactionTimestamp(), txns.get(2).getTransactionTimestamp(), "ACC-S");
        assertThat(evidenceIds(finding)).containsExactly("S-1", "S-2", "S-3");
        assertThat(finding.getExplanation()).contains("inside the 24-hour detection window", "structuring");
    }

    @Test
    void structuringDoesNotFireWhenCandidateTransactionsAreOutsideRollingWindow() {
        List<Transaction> txns = List.of(
                txn("S-1", "ACC-S", "CUST-S", bd("9000.00"), start),
                txn("S-2", "ACC-S", "CUST-S", bd("9500.00"), start.plusSeconds(24 * 3600)),
                txn("S-3", "ACC-S", "CUST-S", bd("9999.99"), start.plusSeconds(47 * 3600)));
        when(repository.findStructuringCandidates(any(), any(), any(), any(), anyLong()))
                .thenReturn(List.of(summary("ACC-S", 3, bd("28499.99"), txns.get(0).getTransactionTimestamp(), txns.get(2).getTransactionTimestamp())));
        when(repository.findStructuringEvidence(eq("ACC-S"), any(), any(), any(), any())).thenReturn(txns);

        List<RuleFinding> findings = new StructuringRule(repository)
                .evaluate(config("STRUCTURING_24H", AmlTypology.STRUCTURING), context(start, end));

        assertThat(findings).isEmpty();
    }

    @Test
    void rapidMovementFiresWhenEightyPercentOfDepositLeavesWithin48Hours() {
        Account account = account("ACC-R", customer("CUST-R"));
        Transaction inflow = txn("R-IN", account, bd("10000.00"), start.plusSeconds(3600), TransactionDirection.CREDIT, TransactionType.WIRE_IN);
        Transaction out1 = txn("R-OUT-1", account, bd("3000.00"), start.plusSeconds(4 * 3600), TransactionDirection.DEBIT, TransactionType.WIRE_OUT);
        Transaction out2 = txn("R-OUT-2", account, bd("5000.00"), start.plusSeconds(47 * 3600), TransactionDirection.DEBIT, TransactionType.TRANSFER_OUT);
        when(repository.findAccountsWithSignificantInflow(eq(start), eq(end), eq(bd("1000.00")))).thenReturn(List.of("ACC-R"));
        when(repository.findAccountWindow(eq("ACC-R"), eq(start), eq(end.plusSeconds(48 * 3600)))).thenReturn(List.of(inflow, out1, out2));

        List<RuleFinding> findings = new RapidMovementRule(repository)
                .evaluate(config("RAPID_MOVEMENT_48H", AmlTypology.RAPID_MOVEMENT), context(start, end));

        assertThat(findings).hasSize(1);
        RuleFinding finding = findings.get(0);
        assertCommonFinding(finding, AmlTypology.RAPID_MOVEMENT, "ACC-R", "CUST-R", inflow.getTransactionTimestamp(), out2.getTransactionTimestamp(), "ACC-R");
        assertThat(evidenceIds(finding)).containsExactly("R-IN", "R-OUT-1", "R-OUT-2");
        assertThat(finding.getExplanation()).contains("80%", "within the 48-hour layering window");
    }

    @Test
    void rapidMovementDoesNotFireBelowEightyPercentOrOutsideWindow() {
        Account account = account("ACC-R", customer("CUST-R"));
        Transaction inflow = txn("R-IN", account, bd("10000.00"), start, TransactionDirection.CREDIT, TransactionType.WIRE_IN);
        Transaction outBelow = txn("R-OUT-LOW", account, bd("7999.00"), start.plusSeconds(2 * 3600), TransactionDirection.DEBIT, TransactionType.WIRE_OUT);
        Transaction outLate = txn("R-OUT-LATE", account, bd("2000.00"), start.plusSeconds(49 * 3600), TransactionDirection.DEBIT, TransactionType.WIRE_OUT);
        when(repository.findAccountsWithSignificantInflow(any(), any(), any())).thenReturn(List.of("ACC-R"));
        when(repository.findAccountWindow(eq("ACC-R"), any(), any())).thenReturn(List.of(inflow, outBelow, outLate));

        List<RuleFinding> findings = new RapidMovementRule(repository)
                .evaluate(config("RAPID_MOVEMENT_48H", AmlTypology.RAPID_MOVEMENT), context(start, end));

        assertThat(findings).isEmpty();
    }

    @Test
    void highRiskJurisdictionFiresAndGroupsByAccountAndCountry() {
        WatchlistEntry iran = watchlistEntry(WatchlistSubjectType.COUNTRY, WatchlistType.SANCTIONS, "IR", "Iran", 30);
        WatchlistService.Snapshot watchlist = new WatchlistService.Snapshot(Map.of("IR", iran), Map.of());
        Transaction first = txn("J-1", "ACC-J", "CUST-J", bd("100.00"), start.plusSeconds(100));
        first.setCounterpartyCountry("ir");
        Transaction second = txn("J-2", "ACC-J", "CUST-J", bd("200.00"), start.plusSeconds(200));
        second.setDestinationCountry("IR");
        when(repository.findHighRiskJurisdictionExposure(eq(start), eq(end), eq(watchlist.blockingCountryCodes()))).thenReturn(List.of(first, second));

        List<RuleFinding> findings = new HighRiskJurisdictionRule(repository)
                .evaluate(config("HIGH_RISK_JURISDICTION", AmlTypology.HIGH_RISK_JURISDICTION), context(start, end, watchlist));

        assertThat(findings).hasSize(1);
        RuleFinding finding = findings.get(0);
        assertCommonFinding(finding, AmlTypology.HIGH_RISK_JURISDICTION, "ACC-J", "CUST-J", first.getTransactionTimestamp(), second.getTransactionTimestamp(), "IR");
        assertThat(finding.getContextUplift()).isEqualTo(10);
        assertThat(evidenceIds(finding)).containsExactly("J-1", "J-2");
        assertThat(finding.getExplanation()).contains("Iran", "Jurisdiction exposure");
    }

    @Test
    void highRiskJurisdictionDoesNotFireWithoutBlockingWatchlistEntry() {
        WatchlistService.Snapshot watchlist = new WatchlistService.Snapshot(
                Map.of("KY", watchlistEntry(WatchlistSubjectType.COUNTRY, WatchlistType.TAX_HAVEN, "KY", "Cayman Islands", 15)),
                Map.of());

        List<RuleFinding> findings = new HighRiskJurisdictionRule(repository)
                .evaluate(config("HIGH_RISK_JURISDICTION", AmlTypology.HIGH_RISK_JURISDICTION), context(start, end, watchlist));

        assertThat(findings).isEmpty();
        verify(repository, never()).findHighRiskJurisdictionExposure(any(), any(), any());
    }

    @Test
    void highRiskCounterpartyFiresForListedCounterpartyName() {
        WatchlistEntry entry = watchlistEntry(WatchlistSubjectType.COUNTERPARTY, WatchlistType.SANCTIONS, "Volkov Trading LLC", "Volkov Trading", 45);
        WatchlistService.Snapshot watchlist = new WatchlistService.Snapshot(Map.of(), Map.of("VOLKOV TRADING LLC", entry));
        Transaction first = txn("C-1", "ACC-C", "CUST-C", bd("700.00"), start.plusSeconds(10));
        first.setCounterpartyName("Volkov Trading, LLC");
        Transaction second = txn("C-2", "ACC-C", "CUST-C", bd("800.00"), start.plusSeconds(20));
        second.setCounterpartyName("VOLKOV TRADING LLC");
        when(repository.findHighRiskCounterpartyExposure(eq(start), eq(end), eq(watchlist.blockingCounterpartyValues()))).thenReturn(List.of(first, second));

        List<RuleFinding> findings = new HighRiskCounterpartyRule(repository)
                .evaluate(config("HIGH_RISK_COUNTERPARTY", AmlTypology.HIGH_RISK_COUNTERPARTY), context(start, end, watchlist));

        assertThat(findings).hasSize(1);
        RuleFinding finding = findings.get(0);
        assertCommonFinding(finding, AmlTypology.HIGH_RISK_COUNTERPARTY, "ACC-C", "CUST-C", first.getTransactionTimestamp(), second.getTransactionTimestamp(), "VOLKOV TRADING LLC");
        assertThat(finding.getContextUplift()).isEqualTo(15);
        assertThat(evidenceIds(finding)).containsExactly("C-1", "C-2");
        assertThat(finding.getExplanation()).contains("counterpartyName", "listed counterparty");
    }

    @Test
    void highRiskCounterpartyDoesNotFireForNonBlockingListType() {
        WatchlistService.Snapshot watchlist = new WatchlistService.Snapshot(
                Map.of(),
                Map.of("POLITICIAN", watchlistEntry(WatchlistSubjectType.INDIVIDUAL, WatchlistType.PEP, "Politician", "PEP", 30)));

        List<RuleFinding> findings = new HighRiskCounterpartyRule(repository)
                .evaluate(config("HIGH_RISK_COUNTERPARTY", AmlTypology.HIGH_RISK_COUNTERPARTY), context(start, end, watchlist));

        assertThat(findings).isEmpty();
        verify(repository, never()).findHighRiskCounterpartyExposure(any(), any(), any());
    }

    @Test
    void behaviouralDeviationFiresWhenDailyVolumeExceedsThreeTimesBaseline() {
        RuleConfig config = config("BEHAVIOURAL_DEVIATION_3X", AmlTypology.BEHAVIOURAL_DEVIATION,
                c -> c.setParameters("{\"minimumBaselineDays\":2,\"minimumDailyValueUsd\":500}"));
        LocalDate day = LocalDate.parse("2026-09-19");
        List<DailyActivity> activity = List.of(
                daily(day.minusDays(3), bd("1000.00"), 2),
                daily(day.minusDays(2), bd("1000.00"), 2),
                daily(day, bd("3000.01"), 7));
        Transaction e1 = txn("B-1", "ACC-B", "CUST-B", bd("1500.00"), start.plusSeconds(3600));
        Transaction e2 = txn("B-2", "ACC-B", "CUST-B", bd("1500.01"), start.plusSeconds(7200));
        when(repository.findActiveCustomerIds(start, end)).thenReturn(List.of("CUST-B"));
        when(repository.findDailyActivity(eq("CUST-B"), any(), eq(end))).thenReturn(activity);
        when(repository.findCustomerWindow(eq("CUST-B"), eq(start), eq(start.plusSeconds(86400)))).thenReturn(List.of(e1, e2));

        List<RuleFinding> findings = new BehaviouralDeviationRule(repository).evaluate(config, context(start, end));

        assertThat(findings).hasSize(1);
        RuleFinding finding = findings.get(0);
        assertCommonFinding(finding, AmlTypology.BEHAVIOURAL_DEVIATION, null, "CUST-B", start, start.plusSeconds(86400), day.toString());
        assertThat(evidenceIds(finding)).containsExactly("B-1", "B-2");
        assertThat(finding.getExplanation()).contains("3.0x", "baseline");
    }

    @Test
    void behaviouralDeviationDoesNotFireAtExactlyThreeTimesBaseline() {
        RuleConfig config = config("BEHAVIOURAL_DEVIATION_3X", AmlTypology.BEHAVIOURAL_DEVIATION,
                c -> c.setParameters("{\"minimumBaselineDays\":2,\"minimumDailyValueUsd\":500}"));
        LocalDate day = LocalDate.parse("2026-09-19");
        when(repository.findActiveCustomerIds(start, end)).thenReturn(List.of("CUST-B"));
        when(repository.findDailyActivity(eq("CUST-B"), any(), eq(end))).thenReturn(List.of(
                daily(day.minusDays(3), bd("1000.00"), 2),
                daily(day.minusDays(2), bd("1000.00"), 2),
                daily(day, bd("3000.00"), 6)));

        List<RuleFinding> findings = new BehaviouralDeviationRule(repository).evaluate(config, context(start, end));

        assertThat(findings).isEmpty();
        verify(repository, never()).findCustomerWindow(any(), any(), any());
    }

    @Test
    void roundAmountFiresForThreeExactMultiplesInsideRollingWindow() {
        RuleConfig config = config("ROUND_AMOUNT_PATTERN", AmlTypology.ROUND_AMOUNT_PATTERN);
        List<Transaction> txns = List.of(
                txn("RA-1", "ACC-RA", "CUST-RA", bd("1000.00"), start.plusSeconds(1)),
                txn("RA-2", "ACC-RA", "CUST-RA", bd("2000.00"), start.plusSeconds(2)),
                txn("RA-3", "ACC-RA", "CUST-RA", bd("5000.00"), start.plusSeconds(3)));
        when(repository.findRoundAmountCandidates(eq(start), eq(end), eq(bd("1000.00")), eq(bd("1000")), eq(3L)))
                .thenReturn(List.of(summary("ACC-RA", 3, bd("8000.00"), txns.get(0).getTransactionTimestamp(), txns.get(2).getTransactionTimestamp())));
        when(repository.findAccountWindow("ACC-RA", start, end)).thenReturn(txns);

        List<RuleFinding> findings = new RoundAmountRule(repository).evaluate(config, context(start, end));

        assertThat(findings).hasSize(1);
        RuleFinding finding = findings.get(0);
        assertCommonFinding(finding, AmlTypology.ROUND_AMOUNT_PATTERN, "ACC-RA", "CUST-RA", txns.get(0).getTransactionTimestamp(), txns.get(2).getTransactionTimestamp(), "ACC-RA");
        assertThat(evidenceIds(finding)).containsExactly("RA-1", "RA-2", "RA-3");
        assertThat(finding.getExplanation()).contains("exact multiple", "round figures");
    }

    @Test
    void roundAmountDoesNotFireForTooFewRoundAmountsAfterFiltering() {
        when(repository.findRoundAmountCandidates(any(), any(), any(), any(), anyLong()))
                .thenReturn(List.of(summary("ACC-RA", 3, bd("4500.00"), start, start.plusSeconds(3))));
        when(repository.findAccountWindow(eq("ACC-RA"), any(), any())).thenReturn(List.of(
                txn("RA-1", "ACC-RA", "CUST-RA", bd("1000.00"), start),
                txn("RA-2", "ACC-RA", "CUST-RA", bd("2500.00"), start.plusSeconds(1)),
                txn("RA-3", "ACC-RA", "CUST-RA", bd("3000.01"), start.plusSeconds(2))));

        List<RuleFinding> findings = new RoundAmountRule(repository)
                .evaluate(config("ROUND_AMOUNT_PATTERN", AmlTypology.ROUND_AMOUNT_PATTERN), context(start, end));

        assertThat(findings).isEmpty();
    }

    @Test
    void dormantReactivationFiresForDormantAccountAboveMaterialityFloor() {
        Account dormant = account("ACC-D", customer("CUST-D"), AccountStatus.DORMANT);
        Transaction first = txn("D-1", dormant, bd("5000.00"), start.plusSeconds(10), TransactionDirection.CREDIT, TransactionType.CASH_DEPOSIT);
        Transaction second = txn("D-2", dormant, bd("6000.00"), start.plusSeconds(20), TransactionDirection.DEBIT, TransactionType.WIRE_OUT);
        when(repository.findDormantAccountActivity(any(), eq(end), eq(bd("5000.00")), any())).thenReturn(List.of(first, second));

        List<RuleFinding> findings = new DormantReactivationRule(repository)
                .evaluate(config("DORMANT_REACTIVATION", AmlTypology.DORMANT_ACCOUNT_REACTIVATION), context(start, end));

        assertThat(findings).hasSize(1);
        RuleFinding finding = findings.get(0);
        assertCommonFinding(finding, AmlTypology.DORMANT_ACCOUNT_REACTIVATION, "ACC-D", "CUST-D", first.getTransactionTimestamp(), second.getTransactionTimestamp(), "ACC-D");
        assertThat(evidenceIds(finding)).containsExactly("D-1", "D-2");
        assertThat(finding.getExplanation()).contains("DORMANT", "significant value");
    }

    @Test
    void dormantReactivationDoesNotFireWhenRepositoryFindsNoDormantActivity() {
        when(repository.findDormantAccountActivity(any(), any(), any(), any())).thenReturn(List.of());

        List<RuleFinding> findings = new DormantReactivationRule(repository)
                .evaluate(config("DORMANT_REACTIVATION", AmlTypology.DORMANT_ACCOUNT_REACTIVATION), context(start, end));

        assertThat(findings).isEmpty();
    }

    private static void assertCommonFinding(RuleFinding finding, AmlTypology typology, String accountId, String customerId, Instant windowStart, Instant windowEnd, String discriminator) {
        assertThat(finding.getTypology()).isEqualTo(typology);
        assertThat(finding.getAccountId()).isEqualTo(accountId);
        assertThat(finding.getCustomerId()).isEqualTo(customerId);
        assertThat(finding.getWindowStart()).isEqualTo(windowStart);
        assertThat(finding.getWindowEnd()).isEqualTo(windowEnd);
        assertThat(finding.getDiscriminator()).isEqualTo(discriminator);
        assertThat(finding.getExplanation()).isNotBlank();
        assertThat(finding.getTitle()).isNotBlank();
        assertThat(finding.getDetailsJson()).isNotBlank();
    }

    private static List<String> evidenceIds(RuleFinding finding) {
        return finding.evidenceTransactions().stream().map(Transaction::getExternalTxnId).toList();
    }

    private static AccountActivitySummary summary(String accountId, long count, BigDecimal total, Instant first, Instant last) {
        return new AccountActivitySummary() {
            public String getAccountId() { return accountId; }
            public long getTxnCount() { return count; }
            public BigDecimal getTotalAmount() { return total; }
            public Instant getFirstAt() { return first; }
            public Instant getLastAt() { return last; }
        };
    }

    private static DailyActivity daily(LocalDate date, BigDecimal total, long count) {
        return new DailyActivity() {
            public LocalDate getActivityDate() { return date; }
            public BigDecimal getTotalValue() { return total; }
            public long getTxnCount() { return count; }
        };
    }
}
