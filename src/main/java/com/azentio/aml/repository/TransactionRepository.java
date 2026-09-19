package com.azentio.aml.repository;

import com.azentio.aml.domain.Transaction;
import com.azentio.aml.domain.enums.ScreeningStatus;
import com.azentio.aml.repository.projection.AccountActivitySummary;
import com.azentio.aml.repository.projection.CustomerBaseline;
import com.azentio.aml.repository.projection.DailyActivity;
import com.azentio.aml.repository.projection.FlowSummary;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Transaction access, including the windowed aggregate queries that back each detection rule.
 *
 * <p>Every rule query is written to be served by one of the two composite indexes
 * {@code (account_id, transaction_timestamp)} and {@code (customer_id, transaction_timestamp)}, and
 * every window is half-open {@code [windowStart, windowEnd)} so adjacent windows neither overlap
 * nor leave a gap. All amount comparisons use {@code baseAmount}, which is already normalised to
 * the platform base currency, so no per-row FX conversion happens during detection.
 *
 * <p>The aggregate queries deliberately return projections rather than entities: a 24-hour window
 * on a busy book is large, and the engine only needs the candidate account plus its totals before
 * deciding whether a full evidence fetch is warranted.
 */
public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    // ---------------------------------------------------------------------
    // Ingestion idempotency
    // ---------------------------------------------------------------------

    Optional<Transaction> findByExternalTxnId(String externalTxnId);

    boolean existsByExternalTxnId(String externalTxnId);

    /** Bulk pre-check so a CSV batch can skip already-loaded rows in one round trip. */
    @Query("select t.externalTxnId from Transaction t where t.externalTxnId in :externalTxnIds")
    List<String> findExistingExternalTxnIds(
            @Param("externalTxnIds") Collection<String> externalTxnIds);

    // ---------------------------------------------------------------------
    // Detection queue
    // ---------------------------------------------------------------------

    Page<Transaction> findByScreeningStatusOrderByTransactionTimestampAsc(
            ScreeningStatus screeningStatus, Pageable pageable);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            """
            update Transaction t
               set t.screeningStatus = :status,
                   t.screenedAt = :screenedAt
             where t.id in :ids
            """)
    int markScreened(
            @Param("ids") Collection<Long> ids,
            @Param("status") ScreeningStatus status,
            @Param("screenedAt") Instant screenedAt);

    // ---------------------------------------------------------------------
    // Rule 1 - CTR-style single-transaction threshold
    // ---------------------------------------------------------------------

    /**
     * Any single transaction at or above the reporting threshold in base currency.
     *
     * @param threshold inclusive lower bound, e.g. 10,000 USD
     */
    @Query(
            """
            select t
              from Transaction t
             where t.transactionTimestamp >= :windowStart
               and t.transactionTimestamp <  :windowEnd
               and t.baseAmount >= :threshold
               and t.status = com.azentio.aml.domain.enums.TransactionStatus.POSTED
             order by t.transactionTimestamp
            """)
    List<Transaction> findThresholdBreaches(
            @Param("windowStart") Instant windowStart,
            @Param("windowEnd") Instant windowEnd,
            @Param("threshold") BigDecimal threshold);

    // ---------------------------------------------------------------------
    // Rule 2 - Structuring (repeated amounts just below the threshold)
    // ---------------------------------------------------------------------

    /**
     * Accounts with at least {@code minOccurrences} transactions inside the window whose individual
     * amounts all sit in the just-below-threshold band.
     *
     * <p>Grouping in the database rather than in Java is what keeps this rule viable at volume: the
     * engine receives only the handful of accounts that already satisfy the count condition.
     */
    @Query(
            """
            select t.account.accountId       as accountId,
                   count(t)                  as txnCount,
                   sum(t.baseAmount)         as totalAmount,
                   min(t.transactionTimestamp) as firstAt,
                   max(t.transactionTimestamp) as lastAt
              from Transaction t
             where t.transactionTimestamp >= :windowStart
               and t.transactionTimestamp <  :windowEnd
               and t.baseAmount >= :minAmount
               and t.baseAmount <= :maxAmount
               and t.status = com.azentio.aml.domain.enums.TransactionStatus.POSTED
             group by t.account.accountId
            having count(t) >= :minOccurrences
            """)
    List<AccountActivitySummary> findStructuringCandidates(
            @Param("windowStart") Instant windowStart,
            @Param("windowEnd") Instant windowEnd,
            @Param("minAmount") BigDecimal minAmount,
            @Param("maxAmount") BigDecimal maxAmount,
            @Param("minOccurrences") long minOccurrences);

    /** The individual transactions behind a structuring candidate, for the evidence pack. */
    @Query(
            """
            select t
              from Transaction t
             where t.account.accountId = :accountId
               and t.transactionTimestamp >= :windowStart
               and t.transactionTimestamp <  :windowEnd
               and t.baseAmount >= :minAmount
               and t.baseAmount <= :maxAmount
               and t.status = com.azentio.aml.domain.enums.TransactionStatus.POSTED
             order by t.transactionTimestamp
            """)
    List<Transaction> findStructuringEvidence(
            @Param("accountId") String accountId,
            @Param("windowStart") Instant windowStart,
            @Param("windowEnd") Instant windowEnd,
            @Param("minAmount") BigDecimal minAmount,
            @Param("maxAmount") BigDecimal maxAmount);

    // ---------------------------------------------------------------------
    // Rule 3 - Rapid movement of funds
    // ---------------------------------------------------------------------

    /** Accounts that received at least {@code minInflow} in the window; the rule's entry point. */
    @Query(
            """
            select distinct t.account.accountId
              from Transaction t
             where t.transactionTimestamp >= :windowStart
               and t.transactionTimestamp <  :windowEnd
               and t.direction = com.azentio.aml.domain.enums.TransactionDirection.CREDIT
               and t.baseAmount >= :minInflow
               and t.status = com.azentio.aml.domain.enums.TransactionStatus.POSTED
            """)
    List<String> findAccountsWithSignificantInflow(
            @Param("windowStart") Instant windowStart,
            @Param("windowEnd") Instant windowEnd,
            @Param("minInflow") BigDecimal minInflow);

    /**
     * Total credits and debits for one account over the window.
     *
     * <p>{@code coalesce} guarantees zero rather than null when a direction has no rows, so callers
     * can divide without a null check.
     */
    @Query(
            """
            select coalesce(sum(case when t.direction = com.azentio.aml.domain.enums.TransactionDirection.CREDIT
                                     then t.baseAmount else 0 end), 0) as inflow,
                   coalesce(sum(case when t.direction = com.azentio.aml.domain.enums.TransactionDirection.DEBIT
                                     then t.baseAmount else 0 end), 0) as outflow
              from Transaction t
             where t.account.accountId = :accountId
               and t.transactionTimestamp >= :windowStart
               and t.transactionTimestamp <  :windowEnd
               and t.status = com.azentio.aml.domain.enums.TransactionStatus.POSTED
            """)
    FlowSummary summariseFlows(
            @Param("accountId") String accountId,
            @Param("windowStart") Instant windowStart,
            @Param("windowEnd") Instant windowEnd);

    /** All movements on an account in the window, ordered so the in-then-out story is readable. */
    @Query(
            """
            select t
              from Transaction t
             where t.account.accountId = :accountId
               and t.transactionTimestamp >= :windowStart
               and t.transactionTimestamp <  :windowEnd
               and t.status = com.azentio.aml.domain.enums.TransactionStatus.POSTED
             order by t.transactionTimestamp
            """)
    List<Transaction> findAccountWindow(
            @Param("accountId") String accountId,
            @Param("windowStart") Instant windowStart,
            @Param("windowEnd") Instant windowEnd);

    // ---------------------------------------------------------------------
    // Rule 4 - High-risk jurisdiction / counterparty
    // ---------------------------------------------------------------------

    /**
     * Transactions touching any of the supplied country codes on either leg.
     *
     * @param countryCodes upper-case ISO 3166-1 alpha-2 codes from the active watchlist
     */
    @Query(
            """
            select t
              from Transaction t
             where t.transactionTimestamp >= :windowStart
               and t.transactionTimestamp <  :windowEnd
               and ( upper(t.counterpartyCountry) in :countryCodes
                  or upper(t.originCountry)       in :countryCodes
                  or upper(t.destinationCountry)  in :countryCodes )
             order by t.transactionTimestamp
            """)
    List<Transaction> findHighRiskJurisdictionExposure(
            @Param("windowStart") Instant windowStart,
            @Param("windowEnd") Instant windowEnd,
            @Param("countryCodes") Collection<String> countryCodes);

    /**
     * Transactions whose counterparty name, bank or account matches a watchlist entry.
     *
     * @param normalizedValues upper-cased watchlist values; the comparison mirrors the
     *     normalisation applied when the watchlist was loaded
     */
    @Query(
            """
            select t
              from Transaction t
             where t.transactionTimestamp >= :windowStart
               and t.transactionTimestamp <  :windowEnd
               and ( upper(t.counterpartyName)    in :normalizedValues
                  or upper(t.counterpartyBank)    in :normalizedValues
                  or upper(t.counterpartyAccount) in :normalizedValues )
             order by t.transactionTimestamp
            """)
    List<Transaction> findHighRiskCounterpartyExposure(
            @Param("windowStart") Instant windowStart,
            @Param("windowEnd") Instant windowEnd,
            @Param("normalizedValues") Collection<String> normalizedValues);

    // ---------------------------------------------------------------------
    // Rule 5 - Behavioural deviation from the customer's own baseline
    // ---------------------------------------------------------------------

    /**
     * The customer's average daily value and count across the days they actually transacted.
     *
     * <p>Native SQL because the aggregation is over a derived per-day table, which JPQL cannot
     * express. Days are bucketed by casting the timestamp to a date in the database session time
     * zone; the application sets that to UTC so bucketing is deterministic across environments.
     */
    @Query(
            value =
                    """
                    select coalesce(avg(d.daily_value), 0) as "avgDailyValue",
                           coalesce(avg(d.daily_count), 0) as "avgDailyCount",
                           count(*)                        as "activeDays"
                      from ( select cast(transaction_timestamp as date) as activity_date,
                                    sum(base_amount)                    as daily_value,
                                    count(*)                            as daily_count
                               from transactions
                              where customer_id = :customerId
                                and transaction_timestamp >= :windowStart
                                and transaction_timestamp <  :windowEnd
                                and status = 'POSTED'
                              group by cast(transaction_timestamp as date) ) d
                    """,
            nativeQuery = true)
    CustomerBaseline findCustomerBaseline(
            @Param("customerId") String customerId,
            @Param("windowStart") Instant windowStart,
            @Param("windowEnd") Instant windowEnd);

    /** Per-day totals for a customer, used to compare "today" against the baseline. */
    @Query(
            value =
                    """
                    select cast(transaction_timestamp as date) as "activityDate",
                           sum(base_amount)                    as "totalValue",
                           count(*)                            as "txnCount"
                      from transactions
                     where customer_id = :customerId
                       and transaction_timestamp >= :windowStart
                       and transaction_timestamp <  :windowEnd
                       and status = 'POSTED'
                     group by cast(transaction_timestamp as date)
                     order by 1
                    """,
            nativeQuery = true)
    List<DailyActivity> findDailyActivity(
            @Param("customerId") String customerId,
            @Param("windowStart") Instant windowStart,
            @Param("windowEnd") Instant windowEnd);

    /** Customers with any activity in the window; the entry point for the deviation sweep. */
    @Query(
            """
            select distinct t.customer.customerId
              from Transaction t
             where t.transactionTimestamp >= :windowStart
               and t.transactionTimestamp <  :windowEnd
               and t.status = com.azentio.aml.domain.enums.TransactionStatus.POSTED
            """)
    List<String> findActiveCustomerIds(
            @Param("windowStart") Instant windowStart, @Param("windowEnd") Instant windowEnd);

    /** Evidence fetch for a customer-level typology. */
    @Query(
            """
            select t
              from Transaction t
             where t.customer.customerId = :customerId
               and t.transactionTimestamp >= :windowStart
               and t.transactionTimestamp <  :windowEnd
               and t.status = com.azentio.aml.domain.enums.TransactionStatus.POSTED
             order by t.transactionTimestamp
            """)
    List<Transaction> findCustomerWindow(
            @Param("customerId") String customerId,
            @Param("windowStart") Instant windowStart,
            @Param("windowEnd") Instant windowEnd);

    // ---------------------------------------------------------------------
    // Supplementary rules
    // ---------------------------------------------------------------------

    /**
     * Accounts with repeated exact-multiple amounts inside the window.
     *
     * <p>Native SQL for {@code mod} on a numeric column, which JPQL does not define portably.
     */
    @Query(
            value =
                    """
                    select account_id                as "accountId",
                           count(*)                  as "txnCount",
                           sum(base_amount)          as "totalAmount",
                           min(transaction_timestamp) as "firstAt",
                           max(transaction_timestamp) as "lastAt"
                      from transactions
                     where transaction_timestamp >= :windowStart
                       and transaction_timestamp <  :windowEnd
                       and base_amount >= :minAmount
                       and mod(base_amount, :roundingMultiple) = 0
                       and status = 'POSTED'
                     group by account_id
                    having count(*) >= :minOccurrences
                    """,
            nativeQuery = true)
    List<AccountActivitySummary> findRoundAmountCandidates(
            @Param("windowStart") Instant windowStart,
            @Param("windowEnd") Instant windowEnd,
            @Param("minAmount") BigDecimal minAmount,
            @Param("roundingMultiple") BigDecimal roundingMultiple,
            @Param("minOccurrences") long minOccurrences);

    /**
     * Significant activity on an account the bank had marked dormant or inactive.
     *
     * <p>The account status is the bank's own classification, so this needs no historical scan.
     */
    @Query(
            """
            select t
              from Transaction t
              join t.account a
             where t.transactionTimestamp >= :windowStart
               and t.transactionTimestamp <  :windowEnd
               and t.baseAmount >= :minAmount
               and a.accountStatus in :dormantStatuses
               and t.status = com.azentio.aml.domain.enums.TransactionStatus.POSTED
             order by t.transactionTimestamp
            """)
    List<Transaction> findDormantAccountActivity(
            @Param("windowStart") Instant windowStart,
            @Param("windowEnd") Instant windowEnd,
            @Param("minAmount") BigDecimal minAmount,
            @Param("dormantStatuses")
                    Collection<com.azentio.aml.domain.enums.AccountStatus> dormantStatuses);

    // ---------------------------------------------------------------------
    // Investigation support
    // ---------------------------------------------------------------------

    Page<Transaction> findByCustomer_CustomerIdOrderByTransactionTimestampDesc(
            String customerId, Pageable pageable);

    Page<Transaction> findByAccount_AccountIdOrderByTransactionTimestampDesc(
            String accountId, Pageable pageable);

    long countByIngestionBatchId(Long ingestionBatchId);
}
