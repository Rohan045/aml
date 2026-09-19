package com.azentio.aml.service.ingestion;

import com.azentio.aml.common.exception.IngestionException;
import com.azentio.aml.config.SentinelProperties;
import com.azentio.aml.domain.Account;
import com.azentio.aml.domain.Customer;
import com.azentio.aml.domain.enums.AccountStatus;
import com.azentio.aml.domain.enums.AuditAction;
import com.azentio.aml.domain.enums.IngestionEntityType;
import com.azentio.aml.domain.enums.IngestionSource;
import com.azentio.aml.domain.enums.IngestionStatus;
import com.azentio.aml.domain.enums.KycStatus;
import com.azentio.aml.domain.enums.RiskRating;
import com.azentio.aml.repository.AccountRepository;
import com.azentio.aml.repository.CustomerRepository;
import com.azentio.aml.security.CurrentUser;
import com.azentio.aml.service.AuditService;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Ingestion of the two master-data feeds the transaction feed depends on: customers and accounts.
 *
 * <p>Kept separate from {@link TransactionIngestionService} because the semantics genuinely differ.
 * A transaction is an immutable event, so a repeat is a duplicate to be skipped. A customer or
 * account is a <em>state</em> record: a re-send carries a refreshed KYC status or risk rating and
 * must overwrite what is held, or the platform would screen tomorrow's transactions against
 * yesterday's risk profile. Master-data ingestion is therefore an upsert.
 *
 * <p>Load order matters and is enforced rather than assumed: an account row whose customer is not
 * on file is rejected with {@code UNKNOWN_CUSTOMER} instead of being silently dropped by a
 * foreign-key error at flush time, which would take the whole chunk down with it.
 *
 * <p>In {@link IngestionResult}, {@code succeeded} counts rows written (inserted or updated) and
 * {@code duplicates} counts how many of those were updates to a record already held.
 */
@Service
public class MasterDataIngestionService {

    private static final Logger log = LoggerFactory.getLogger(MasterDataIngestionService.class);

    /** Chunk size for the existence pre-check; keeps the generated {@code IN (...)} list sane. */
    private static final int LOOKUP_CHUNK = 500;

    private final CustomerRepository customerRepository;
    private final AccountRepository accountRepository;
    private final IngestionWriter writer;
    private final AuditService auditService;
    private final Validator validator;
    private final SentinelProperties properties;

    public MasterDataIngestionService(
            CustomerRepository customerRepository,
            AccountRepository accountRepository,
            IngestionWriter writer,
            AuditService auditService,
            Validator validator,
            SentinelProperties properties) {
        this.customerRepository = customerRepository;
        this.accountRepository = accountRepository;
        this.writer = writer;
        this.auditService = auditService;
        this.validator = validator;
        this.properties = properties;
    }

    // ------------------------------------------------------------------
    // Customers
    // ------------------------------------------------------------------

    /**
     * Loads or refreshes customer KYC records.
     *
     * @param fileName original upload name for traceability, {@code null} for REST batches
     */
    public IngestionResult ingestCustomers(
            List<CustomerPayload> payloads, IngestionSource source, String fileName) {
        guardBatchSize(payloads);
        Instant startedAt = Instant.now();
        var batch = openBatch(source, IngestionEntityType.CUSTOMER, fileName, payloads.size());

        List<IngestionResult.RecordError> errors = new ArrayList<>();
        Set<String> seenInFile = new HashSet<>();
        Set<String> existing = existingCustomerIds(payloads);
        Map<String, Customer> loaded = loadCustomers(existing);

        List<Customer> accepted = new ArrayList<>();
        int updates = 0;
        int recordNumber = 0;

        for (CustomerPayload payload : payloads) {
            recordNumber++;
            List<IngestionResult.RecordError> violations =
                    validate(payload, recordNumber, payload.customerId());
            if (!violations.isEmpty()) {
                errors.addAll(violations);
                continue;
            }
            // A file that repeats the same customer would otherwise produce two rows competing for
            // the same primary key inside one flush; last one in the file wins is not a defensible
            // rule, so the repeat is rejected and reported.
            if (!seenInFile.add(payload.customerId())) {
                errors.add(
                        error(
                                recordNumber,
                                payload.customerId(),
                                "customerId",
                                "DUPLICATE_IN_FILE",
                                "Customer " + payload.customerId() + " appears more than once"));
                continue;
            }
            Customer target = loaded.get(payload.customerId());
            if (target == null) {
                target = new Customer();
                target.setCustomerId(payload.customerId());
            } else {
                updates++;
            }
            apply(payload, target);
            accepted.add(target);
        }

        List<Customer> persisted = new ArrayList<>(accepted.size());
        int chunkSize = properties.getIngestion().getBatchSize();
        for (int from = 0; from < accepted.size(); from += chunkSize) {
            int to = Math.min(from + chunkSize, accepted.size());
            persisted.addAll(writer.saveCustomers(accepted.subList(from, to)));
        }

        return close(
                batch.getId(),
                IngestionEntityType.CUSTOMER,
                payloads.size(),
                persisted.size(),
                errors,
                updates,
                startedAt);
    }

    // ------------------------------------------------------------------
    // Accounts
    // ------------------------------------------------------------------

    /** Loads or refreshes accounts. Every row must reference a customer already on file. */
    public IngestionResult ingestAccounts(
            List<AccountPayload> payloads, IngestionSource source, String fileName) {
        guardBatchSize(payloads);
        Instant startedAt = Instant.now();
        var batch = openBatch(source, IngestionEntityType.ACCOUNT, fileName, payloads.size());

        List<IngestionResult.RecordError> errors = new ArrayList<>();
        Set<String> seenInFile = new HashSet<>();
        Map<String, Account> loaded = loadAccounts(existingAccountIds(payloads));
        Map<String, Customer> owners = loadCustomers(referencedCustomerIds(payloads));

        List<Account> accepted = new ArrayList<>();
        int updates = 0;
        int recordNumber = 0;

        for (AccountPayload payload : payloads) {
            recordNumber++;
            List<IngestionResult.RecordError> violations =
                    validate(payload, recordNumber, payload.accountId());
            if (!violations.isEmpty()) {
                errors.addAll(violations);
                continue;
            }
            if (!seenInFile.add(payload.accountId())) {
                errors.add(
                        error(
                                recordNumber,
                                payload.accountId(),
                                "accountId",
                                "DUPLICATE_IN_FILE",
                                "Account " + payload.accountId() + " appears more than once"));
                continue;
            }
            Customer owner = owners.get(payload.customerId());
            if (owner == null) {
                errors.add(
                        error(
                                recordNumber,
                                payload.accountId(),
                                "customerId",
                                "UNKNOWN_CUSTOMER",
                                "Customer "
                                        + payload.customerId()
                                        + " is not on file; load the customer feed first"));
                continue;
            }
            if (payload.closeDate() != null && payload.closeDate().isBefore(payload.openDate())) {
                errors.add(
                        error(
                                recordNumber,
                                payload.accountId(),
                                "closeDate",
                                "INVALID_DATE_RANGE",
                                "Close date precedes the open date"));
                continue;
            }
            Account target = loaded.get(payload.accountId());
            if (target == null) {
                target = new Account();
                target.setAccountId(payload.accountId());
            } else {
                updates++;
            }
            apply(payload, target, owner);
            accepted.add(target);
        }

        List<Account> persisted = new ArrayList<>(accepted.size());
        int chunkSize = properties.getIngestion().getBatchSize();
        for (int from = 0; from < accepted.size(); from += chunkSize) {
            int to = Math.min(from + chunkSize, accepted.size());
            persisted.addAll(writer.saveAccounts(accepted.subList(from, to)));
        }

        return close(
                batch.getId(),
                IngestionEntityType.ACCOUNT,
                payloads.size(),
                persisted.size(),
                errors,
                updates,
                startedAt);
    }

    // ------------------------------------------------------------------
    // Mapping
    // ------------------------------------------------------------------

    private void apply(CustomerPayload payload, Customer target) {
        target.setFirstName(payload.firstName());
        target.setLastName(payload.lastName());
        target.setGender(payload.gender());
        target.setDateOfBirth(payload.dateOfBirth());
        target.setAge(payload.age());
        target.setEmail(payload.email());
        target.setPhoneNumber(payload.phoneNumber());
        target.setNationalId(payload.nationalId());
        target.setCity(payload.city());
        target.setState(payload.state());
        target.setCountry(upper(payload.country()));
        target.setPostalCode(payload.postalCode());
        target.setOccupation(payload.occupation());
        target.setAnnualIncome(payload.annualIncome());
        target.setMaritalStatus(payload.maritalStatus());
        target.setEducationLevel(payload.educationLevel());
        target.setEmploymentStatus(payload.employmentStatus());
        target.setCustomerSince(payload.customerSince());
        target.setCustomerSegment(payload.customerSegment());
        // Defaults keep the columns NOT NULL without forcing every feed to supply them.
        target.setKycStatus(payload.kycStatus() == null ? KycStatus.PENDING : payload.kycStatus());
        target.setRiskRating(
                payload.riskRating() == null ? RiskRating.LOW : payload.riskRating());
        target.setPoliticallyExposed(
                payload.politicallyExposed() == null
                        ? Boolean.FALSE
                        : payload.politicallyExposed());
        target.setPreferredChannel(payload.preferredChannel());
        target.setEmailVerified(payload.emailVerified());
        target.setPhoneVerified(payload.phoneVerified());
        target.setComplaintsLastYear(payload.complaintsLastYear());
    }

    private void apply(AccountPayload payload, Account target, Customer owner) {
        target.setCustomer(owner);
        target.setAccountType(payload.accountType());
        target.setAccountStatus(
                payload.accountStatus() == null ? AccountStatus.ACTIVE : payload.accountStatus());
        target.setCurrency(upper(payload.currency()));
        target.setOpenDate(payload.openDate());
        target.setCloseDate(payload.closeDate());
        // An account inherits its owner's rating unless compliance has overridden it in the feed.
        target.setRiskRating(
                payload.riskRating() == null ? owner.getRiskRating() : payload.riskRating());
        target.setBranchCode(payload.branchCode());
        target.setBranchCity(payload.branchCity());
        target.setCurrentBalance(payload.currentBalance());
        target.setAvgMonthlyBalance6m(payload.avgMonthlyBalance6m());
        target.setCreditLimit(payload.creditLimit());
        target.setCreditUtilizationPct(payload.creditUtilizationPct());
        target.setOverdraftEnabled(payload.overdraftEnabled());
        target.setCardType(payload.cardType());
        target.setJointAccount(payload.jointAccount());
        target.setNumLinkedDevices(payload.numLinkedDevices());
        target.setMobileBankingEnrolled(payload.mobileBankingEnrolled());
        target.setLastLoginDate(payload.lastLoginDate());
        target.setAvgMonthlyTxnCount(payload.avgMonthlyTxnCount());
        target.setAccountTier(payload.accountTier());
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private void guardBatchSize(List<?> payloads) {
        if (payloads == null || payloads.isEmpty()) {
            throw new IngestionException("The batch contains no records");
        }
        int max = properties.getIngestion().getMaxRecordsPerBatch();
        if (payloads.size() > max) {
            throw new IngestionException(
                    "Batch of "
                            + payloads.size()
                            + " records exceeds the configured maximum of "
                            + max
                            + "; split the file and resubmit");
        }
    }

    private com.azentio.aml.domain.IngestionBatch openBatch(
            IngestionSource source, IngestionEntityType entityType, String fileName, int size) {
        var batch = writer.openBatch(source, entityType, fileName, CurrentUser.username(), size);
        auditService.record(
                "IngestionBatch",
                batch.getBatchReference(),
                AuditAction.INGESTION_STARTED,
                AuditService.detailsOf(
                        "source", source, "entityType", entityType, "records", size, "fileName",
                        fileName));
        return batch;
    }

    private IngestionResult close(
            Long batchId,
            IngestionEntityType entityType,
            int total,
            int persisted,
            List<IngestionResult.RecordError> errors,
            int updates,
            Instant startedAt) {
        IngestionStatus status =
                errors.isEmpty()
                        ? IngestionStatus.COMPLETED
                        : (persisted == 0
                                ? IngestionStatus.FAILED
                                : IngestionStatus.COMPLETED_WITH_ERRORS);
        String summary =
                errors.isEmpty()
                        ? null
                        : errors.size() + " record(s) rejected; first: " + errors.get(0).errorMessage();
        var closed =
                writer.closeBatch(
                        batchId, status, persisted, errors.size(), updates, summary, errors, null);

        auditService.record(
                "IngestionBatch",
                closed.getBatchReference(),
                AuditAction.INGESTION_COMPLETED,
                AuditService.detailsOf(
                        "status", status,
                        "entityType", entityType,
                        "succeeded", persisted,
                        "failed", errors.size(),
                        "updated", updates));
        log.info(
                "Batch {} ({}) finished: {} written ({} updates), {} rejected in {} ms",
                closed.getBatchReference(),
                entityType,
                persisted,
                updates,
                errors.size(),
                Instant.now().toEpochMilli() - startedAt.toEpochMilli());

        return new IngestionResult(
                closed.getBatchReference(),
                entityType,
                status,
                total,
                persisted,
                errors.size(),
                updates,
                0L,
                0L,
                startedAt,
                closed.getCompletedAt(),
                errors);
    }

    private <T> List<IngestionResult.RecordError> validate(
            T payload, int recordNumber, String identifier) {
        List<IngestionResult.RecordError> errors = new ArrayList<>();
        for (ConstraintViolation<T> violation : validator.validate(payload)) {
            errors.add(
                    error(
                            recordNumber,
                            identifier,
                            violation.getPropertyPath().toString(),
                            "VALIDATION_FAILED",
                            violation.getMessage()));
        }
        return errors;
    }

    private static IngestionResult.RecordError error(
            int recordNumber, String identifier, String field, String code, String message) {
        return new IngestionResult.RecordError(recordNumber, identifier, field, code, message);
    }

    private Set<String> existingCustomerIds(List<CustomerPayload> payloads) {
        List<String> ids =
                payloads.stream()
                        .map(CustomerPayload::customerId)
                        .filter(id -> id != null && !id.isBlank())
                        .distinct()
                        .toList();
        return lookup(ids, customerRepository::findExistingIds);
    }

    private Set<String> existingAccountIds(List<AccountPayload> payloads) {
        List<String> ids =
                payloads.stream()
                        .map(AccountPayload::accountId)
                        .filter(id -> id != null && !id.isBlank())
                        .distinct()
                        .toList();
        return lookup(ids, accountRepository::findExistingIds);
    }

    private Set<String> referencedCustomerIds(List<AccountPayload> payloads) {
        Set<String> ids = new HashSet<>();
        for (AccountPayload payload : payloads) {
            if (payload.customerId() != null && !payload.customerId().isBlank()) {
                ids.add(payload.customerId());
            }
        }
        return ids;
    }

    /** Chunked existence lookup so a 200k-row file does not build one enormous {@code IN} list. */
    private Set<String> lookup(
            List<String> ids, java.util.function.Function<List<String>, List<String>> query) {
        Set<String> found = new HashSet<>();
        for (int from = 0; from < ids.size(); from += LOOKUP_CHUNK) {
            int to = Math.min(from + LOOKUP_CHUNK, ids.size());
            found.addAll(query.apply(ids.subList(from, to)));
        }
        return found;
    }

    private Map<String, Customer> loadCustomers(Set<String> ids) {
        Map<String, Customer> loaded = new HashMap<>();
        if (ids.isEmpty()) {
            return loaded;
        }
        for (Customer customer : customerRepository.findAllById(ids)) {
            loaded.put(customer.getCustomerId(), customer);
        }
        return loaded;
    }

    private Map<String, Account> loadAccounts(Set<String> ids) {
        Map<String, Account> loaded = new HashMap<>();
        if (ids.isEmpty()) {
            return loaded;
        }
        for (Account account : accountRepository.findAllById(ids)) {
            loaded.put(account.getAccountId(), account);
        }
        return loaded;
    }

    private static String upper(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }
}
