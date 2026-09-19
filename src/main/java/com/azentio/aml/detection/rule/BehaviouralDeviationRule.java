package com.azentio.aml.detection.rule;

import com.azentio.aml.common.Format;
import com.azentio.aml.common.Json;
import com.azentio.aml.detection.DetectionContext;
import com.azentio.aml.detection.RuleFinding;
import com.azentio.aml.detection.RuleParameters;
import com.azentio.aml.domain.RuleConfig;
import com.azentio.aml.domain.Transaction;
import com.azentio.aml.domain.enums.AmlTypology;
import com.azentio.aml.repository.TransactionRepository;
import com.azentio.aml.repository.projection.DailyActivity;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Business rule 5 - a customer's daily transaction value or count exceeding three times their own
 * 90-day rolling average.
 *
 * <p>The comparison is strictly against the customer's own history, never a peer group: a corporate
 * treasury moving millions daily is normal, and a retail customer suddenly moving thousands is not.
 * That is precisely the behaviour amount thresholds cannot see.
 *
 * <p>Two guards keep this from flooding the queue. The baseline must be built from a minimum number
 * of active days, so a customer onboarded last week is not judged against a two-day history; and a
 * floor on the day's value suppresses the arithmetically true but operationally useless "spent 30
 * instead of an average of 5" alert. Both are tunable through the rule's parameters.
 */
@Component
public class BehaviouralDeviationRule extends AbstractDetectionRule {

    private static final Logger log = LoggerFactory.getLogger(BehaviouralDeviationRule.class);
    private static final String RULE_CODE = "BEHAVIOURAL_DEVIATION_3X";
    private static final BigDecimal DEFAULT_MULTIPLIER = new BigDecimal("3.00");
    private static final int DEFAULT_LOOKBACK_DAYS = 90;
    private static final int DEFAULT_MIN_BASELINE_DAYS = 14;
    private static final BigDecimal DEFAULT_MIN_DAILY_VALUE = new BigDecimal("500.00");

    private final TransactionRepository transactionRepository;

    public BehaviouralDeviationRule(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    @Override
    public String ruleCode() {
        return RULE_CODE;
    }

    @Override
    public AmlTypology typology() {
        return AmlTypology.BEHAVIOURAL_DEVIATION;
    }

    @Override
    public List<RuleFinding> evaluate(RuleConfig config, DetectionContext context) {
        BigDecimal multiplier =
                config.getDeviationMultiplier() == null
                        ? DEFAULT_MULTIPLIER
                        : config.getDeviationMultiplier();
        int lookbackDays =
                config.getLookbackDays() == null || config.getLookbackDays() < 1
                        ? DEFAULT_LOOKBACK_DAYS
                        : config.getLookbackDays();
        RuleParameters parameters = RuleParameters.of(config);
        int minBaselineDays = parameters.integer("minimumBaselineDays", DEFAULT_MIN_BASELINE_DAYS);
        BigDecimal minDailyValue =
                parameters.decimal("minimumDailyValueUsd", DEFAULT_MIN_DAILY_VALUE);

        List<String> customerIds =
                context.isStreaming()
                        ? List.of(customerIdOf(context.trigger()))
                        : transactionRepository.findActiveCustomerIds(
                                context.windowStart(), context.windowEnd());

        List<RuleFinding> findings = new ArrayList<>();
        for (String customerId : customerIds) {
            findings.addAll(
                    evaluateCustomer(
                            config,
                            context,
                            customerId,
                            multiplier,
                            lookbackDays,
                            minBaselineDays,
                            minDailyValue));
        }
        return findings;
    }

    private List<RuleFinding> evaluateCustomer(
            RuleConfig config,
            DetectionContext context,
            String customerId,
            BigDecimal multiplier,
            int lookbackDays,
            int minBaselineDays,
            BigDecimal minDailyValue) {

        LocalDate firstDayUnderTest = toUtcDate(context.windowStart());
        LocalDate lastDayUnderTest = toUtcDate(context.windowEnd().minusMillis(1));
        Instant historyStart =
                firstDayUnderTest.minusDays(lookbackDays).atStartOfDay(ZoneOffset.UTC).toInstant();

        // One query per customer covers both the baseline and the days being tested.
        List<DailyActivity> activity =
                transactionRepository.findDailyActivity(
                        customerId, historyStart, context.windowEnd());
        if (activity.isEmpty()) {
            return List.of();
        }

        List<RuleFinding> findings = new ArrayList<>();
        for (DailyActivity day : activity) {
            LocalDate date = day.getActivityDate();
            if (date.isBefore(firstDayUnderTest) || date.isAfter(lastDayUnderTest)) {
                continue;
            }
            Baseline baseline = baselineBefore(activity, date, lookbackDays);
            if (baseline.activeDays() < minBaselineDays) {
                log.debug(
                        "{}: customer {} has only {} active baseline days; skipping {}",
                        RULE_CODE,
                        customerId,
                        baseline.activeDays(),
                        date);
                continue;
            }
            if (day.getTotalValue().compareTo(minDailyValue) < 0) {
                continue;
            }

            BigDecimal valueTrigger = baseline.averageValue().multiply(multiplier);
            BigDecimal countTrigger =
                    BigDecimal.valueOf(baseline.averageCount()).multiply(multiplier);
            boolean valueBreach = day.getTotalValue().compareTo(valueTrigger) > 0;
            boolean countBreach =
                    BigDecimal.valueOf(day.getTxnCount()).compareTo(countTrigger) > 0;
            if (!valueBreach && !countBreach) {
                continue;
            }

            findings.add(
                    toFinding(
                            config,
                            context,
                            customerId,
                            date,
                            day,
                            baseline,
                            multiplier,
                            lookbackDays,
                            valueBreach,
                            countBreach));
        }
        return findings;
    }

    /**
     * Average daily value and count over the active days preceding {@code date}.
     *
     * <p>Averaging over days the customer actually transacted, rather than over every calendar day,
     * keeps the baseline meaningful for customers who transact in bursts - dividing by 90 when only
     * 12 days saw activity would depress the average and make almost any day look like a spike.
     */
    private Baseline baselineBefore(
            List<DailyActivity> activity, LocalDate date, int lookbackDays) {
        LocalDate from = date.minusDays(lookbackDays);
        BigDecimal totalValue = BigDecimal.ZERO;
        long totalCount = 0;
        int days = 0;
        for (DailyActivity day : activity) {
            LocalDate activityDate = day.getActivityDate();
            if (activityDate.isBefore(from) || !activityDate.isBefore(date)) {
                continue;
            }
            totalValue = totalValue.add(day.getTotalValue());
            totalCount += day.getTxnCount();
            days++;
        }
        if (days == 0) {
            return new Baseline(BigDecimal.ZERO, 0d, 0);
        }
        BigDecimal averageValue =
                totalValue.divide(BigDecimal.valueOf(days), 2, RoundingMode.HALF_UP);
        return new Baseline(averageValue, (double) totalCount / days, days);
    }

    private RuleFinding toFinding(
            RuleConfig config,
            DetectionContext context,
            String customerId,
            LocalDate date,
            DailyActivity day,
            Baseline baseline,
            BigDecimal multiplier,
            int lookbackDays,
            boolean valueBreach,
            boolean countBreach) {

        Instant dayStart = date.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant dayEnd = dayStart.plus(1, ChronoUnit.DAYS);
        List<Transaction> evidence =
                transactionRepository.findCustomerWindow(customerId, dayStart, dayEnd);
        String currency = baseCurrencyOf(evidence);

        BigDecimal valueRatio =
                baseline.averageValue().signum() == 0
                        ? BigDecimal.ZERO
                        : day.getTotalValue()
                                .divide(baseline.averageValue(), 2, RoundingMode.HALF_UP);

        String breachDescription =
                valueBreach && countBreach
                        ? "both the value and the number of transactions"
                        : valueBreach ? "the value of transactions" : "the number of transactions";

        String explanation =
                ("On %s customer %s transacted %s across %d transactions. Their %d-day baseline, "
                                + "built from %d days of actual activity, is %s and %.1f "
                                + "transactions per active day - so %s exceeded the %s trigger "
                                + "(day value is %s the baseline). A sharp, unexplained departure "
                                + "from a customer's own established pattern is a laundering "
                                + "indicator that fixed amount thresholds cannot detect.")
                        .formatted(
                                date,
                                customerId,
                                Format.money(day.getTotalValue(), currency),
                                day.getTxnCount(),
                                lookbackDays,
                                baseline.activeDays(),
                                Format.money(baseline.averageValue(), currency),
                                baseline.averageCount(),
                                breachDescription,
                                Format.multiplier(multiplier),
                                Format.multiplier(valueRatio));

        RuleFinding.RuleFindingBuilder builder =
                RuleFinding.builder()
                        .ruleCode(RULE_CODE)
                        .ruleName(config.getRuleName())
                        .ruleVersion(config.getConfigVersion())
                        .typology(typology())
                        .severity(config.getSeverity())
                        .ruleWeight(weightOf(config))
                        .customerId(customerId)
                        .accountId(null)
                        .discriminator(date.toString())
                        .title(
                                "Daily activity %s the customer baseline on %s"
                                        .formatted(Format.multiplier(valueRatio), date))
                        .explanation(explanation)
                        .detailsJson(
                                Json.of(
                                        "activityDate", date.toString(),
                                        "observedDailyValue", day.getTotalValue(),
                                        "observedDailyCount", day.getTxnCount(),
                                        "baselineAverageValue", baseline.averageValue(),
                                        "baselineAverageCount", baseline.averageCount(),
                                        "baselineActiveDays", baseline.activeDays(),
                                        "lookbackDays", lookbackDays,
                                        "deviationMultiplier", multiplier,
                                        "observedValueMultiple", valueRatio,
                                        "valueBreach", valueBreach,
                                        "countBreach", countBreach))
                        .windowStart(dayStart)
                        .windowEnd(dayEnd)
                        .detectedAt(context.evaluatedAt())
                        .totalAmount(day.getTotalValue())
                        .transactionCount((int) day.getTxnCount());

        for (Transaction transaction : evidence) {
            builder.evidence(RuleFinding.EvidenceItem.of(transaction, "DEVIATION_DAY"));
        }
        return builder.build();
    }

    private static LocalDate toUtcDate(Instant instant) {
        return instant.atZone(ZoneOffset.UTC).toLocalDate();
    }

    private record Baseline(BigDecimal averageValue, double averageCount, int activeDays) {}
}
