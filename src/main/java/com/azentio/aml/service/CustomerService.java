package com.azentio.aml.service;

import com.azentio.aml.common.exception.NotFoundException;
import com.azentio.aml.domain.Account;
import com.azentio.aml.domain.Customer;
import com.azentio.aml.domain.Transaction;
import com.azentio.aml.domain.enums.KycStatus;
import com.azentio.aml.domain.enums.RiskRating;
import com.azentio.aml.repository.AccountRepository;
import com.azentio.aml.repository.CustomerRepository;
import com.azentio.aml.repository.TransactionRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read access to customer, account and transaction master data for the investigation UI.
 *
 * <p>Deliberately query-only. Customer and account records enter the platform through the ingestion
 * feeds, which is the single place validation, referential integrity and batch auditing live; an
 * analyst-facing write path would be a second, unaudited way for master data to change.
 *
 * <p>Every transaction query is bounded by an explicit time window. An unbounded "all transactions
 * for this customer" read would degrade as the book grows and is never what an investigation
 * actually needs - the analyst is always asking about a period.
 */
@Service
public class CustomerService {

    /** Timeline window used when the caller does not supply one. */
    private static final int DEFAULT_TIMELINE_DAYS = 90;

    /** Upper bound on a requested window, so one request cannot table-scan the history. */
    private static final int MAX_TIMELINE_DAYS = 730;

    private final CustomerRepository customerRepository;
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;

    public CustomerService(
            CustomerRepository customerRepository,
            AccountRepository accountRepository,
            TransactionRepository transactionRepository) {
        this.customerRepository = customerRepository;
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
    }

    // ------------------------------------------------------------------
    // Customers
    // ------------------------------------------------------------------

    /** Customer list, optionally narrowed to one risk band or KYC state. */
    @Transactional(readOnly = true)
    public Page<Customer> list(RiskRating riskRating, KycStatus kycStatus, Pageable pageable) {
        if (riskRating != null) {
            return customerRepository.findByRiskRating(riskRating, pageable);
        }
        if (kycStatus != null) {
            return customerRepository.findByKycStatus(kycStatus, pageable);
        }
        return customerRepository.findAll(pageable);
    }

    @Transactional(readOnly = true)
    public Customer require(String customerId) {
        return customerRepository
                .findById(customerId)
                .orElseThrow(() -> NotFoundException.of("Customer", customerId));
    }

    @Transactional(readOnly = true)
    public List<Customer> politicallyExposed() {
        return customerRepository.findByPoliticallyExposedTrue();
    }

    // ------------------------------------------------------------------
    // Accounts
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Account> accountsOf(String customerId) {
        // Checked rather than returning an empty list, so an unknown id is a 404 and not a
        // customer who appears to hold no accounts.
        require(customerId);
        return accountRepository.findByCustomer_CustomerId(customerId);
    }

    @Transactional(readOnly = true)
    public Account requireAccount(String accountId) {
        return accountRepository
                .findById(accountId)
                .orElseThrow(() -> NotFoundException.of("Account", accountId));
    }

    // ------------------------------------------------------------------
    // Transaction timelines
    // ------------------------------------------------------------------

    /**
     * The customer's transactions across every account they hold, in chronological order - the
     * timeline an analyst reads to decide whether an alert describes real layering or a salary.
     */
    @Transactional(readOnly = true)
    public List<Transaction> customerTimeline(String customerId, Instant from, Instant to) {
        require(customerId);
        Window window = Window.resolve(from, to);
        return transactionRepository.findCustomerWindow(customerId, window.from(), window.to());
    }

    @Transactional(readOnly = true)
    public List<Transaction> accountTimeline(String accountId, Instant from, Instant to) {
        requireAccount(accountId);
        Window window = Window.resolve(from, to);
        return transactionRepository.findAccountWindow(accountId, window.from(), window.to());
    }

    @Transactional(readOnly = true)
    public Transaction requireTransaction(String externalTxnId) {
        return transactionRepository
                .findByExternalTxnId(externalTxnId)
                .orElseThrow(() -> NotFoundException.of("Transaction", externalTxnId));
    }

    /**
     * A validated, bounded query window.
     *
     * <p>The end is exclusive, matching the repository queries, so a transaction on the boundary is
     * counted once rather than appearing in two adjacent windows.
     */
    private record Window(Instant from, Instant to) {

        static Window resolve(Instant from, Instant to) {
            Instant end = to == null ? Instant.now() : to;
            Instant start =
                    from == null ? end.minus(DEFAULT_TIMELINE_DAYS, ChronoUnit.DAYS) : from;
            if (!start.isBefore(end)) {
                throw new IllegalArgumentException("The window start must precede its end");
            }
            if (start.isBefore(end.minus(MAX_TIMELINE_DAYS, ChronoUnit.DAYS))) {
                throw new IllegalArgumentException(
                        "The requested window exceeds the maximum of "
                                + MAX_TIMELINE_DAYS
                                + " days; narrow the range");
            }
            return new Window(start, end);
        }
    }
}
