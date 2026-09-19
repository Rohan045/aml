package com.azentio.aml.service.ingestion;

import com.azentio.aml.common.exception.IngestionException;
import com.azentio.aml.domain.enums.AccountStatus;
import com.azentio.aml.domain.enums.AccountTier;
import com.azentio.aml.domain.enums.AccountType;
import com.azentio.aml.domain.enums.CardType;
import com.azentio.aml.domain.enums.Channel;
import com.azentio.aml.domain.enums.CustomerSegment;
import com.azentio.aml.domain.enums.EducationLevel;
import com.azentio.aml.domain.enums.EmploymentStatus;
import com.azentio.aml.domain.enums.Gender;
import com.azentio.aml.domain.enums.IngestionEntityType;
import com.azentio.aml.domain.enums.IngestionSource;
import com.azentio.aml.domain.enums.IngestionStatus;
import com.azentio.aml.domain.enums.KycStatus;
import com.azentio.aml.domain.enums.MaritalStatus;
import com.azentio.aml.domain.enums.RiskRating;
import com.azentio.aml.domain.enums.TransactionDirection;
import com.azentio.aml.domain.enums.TransactionStatus;
import com.azentio.aml.domain.enums.TransactionType;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * Bulk CSV loading for the three data feeds.
 *
 * <p>This class owns only the translation from a CSV row to a feed payload; validation, referential
 * integrity, idempotency and persistence stay in {@link TransactionIngestionService} and
 * {@link MasterDataIngestionService}, so a record uploaded as a file and the same record posted as
 * JSON are subjected to exactly the same checks.
 *
 * <p>A row that cannot even be mapped - an unparseable date, a non-numeric amount - is reported as
 * a rejected record rather than aborting the upload. Those mapping failures are merged into the
 * {@link IngestionResult} alongside the validation failures found downstream, so the operator
 * receives one complete list of everything wrong with the file instead of discovering the problems
 * one upload at a time.
 */
@Service
public class CsvIngestionService {

    private static final Logger log = LoggerFactory.getLogger(CsvIngestionService.class);

    private final TransactionIngestionService transactionIngestionService;
    private final MasterDataIngestionService masterDataIngestionService;

    public CsvIngestionService(
            TransactionIngestionService transactionIngestionService,
            MasterDataIngestionService masterDataIngestionService) {
        this.transactionIngestionService = transactionIngestionService;
        this.masterDataIngestionService = masterDataIngestionService;
    }

    // ------------------------------------------------------------------
    // Entry points
    // ------------------------------------------------------------------

    public IngestionResult ingestCustomers(MultipartFile file) {
        Mapped<CustomerPayload> mapped = map(file, CsvIngestionService::toCustomer, "customer_id");
        return merge(
                mapped,
                IngestionEntityType.CUSTOMER,
                payloads ->
                        masterDataIngestionService.ingestCustomers(
                                payloads, IngestionSource.CSV_UPLOAD, file.getOriginalFilename()));
    }

    public IngestionResult ingestAccounts(MultipartFile file) {
        Mapped<AccountPayload> mapped = map(file, CsvIngestionService::toAccount, "account_id");
        return merge(
                mapped,
                IngestionEntityType.ACCOUNT,
                payloads ->
                        masterDataIngestionService.ingestAccounts(
                                payloads, IngestionSource.CSV_UPLOAD, file.getOriginalFilename()));
    }

    public IngestionResult ingestTransactions(MultipartFile file) {
        Mapped<TransactionPayload> mapped =
                map(file, CsvIngestionService::toTransaction, "transaction_id");
        return merge(
                mapped,
                IngestionEntityType.TRANSACTION,
                payloads ->
                        transactionIngestionService.ingest(
                                payloads, IngestionSource.CSV_UPLOAD, file.getOriginalFilename()));
    }

    // ------------------------------------------------------------------
    // Row mapping
    // ------------------------------------------------------------------

    static CustomerPayload toCustomer(CsvSupport.Row row) {
        return new CustomerPayload(
                row.any("customer_id", "customerId"),
                row.any("first_name", "firstName"),
                row.any("last_name", "lastName"),
                row.enumeration(Gender.class, "gender", null),
                row.date("date_of_birth"),
                row.integer("age"),
                row.get("email"),
                row.any("phone_number", "phone"),
                row.any("national_id", "nationalId"),
                row.get("city"),
                row.get("state"),
                row.upper("country"),
                row.any("postal_code", "pincode"),
                row.get("occupation"),
                row.decimal("annual_income"),
                row.enumeration(MaritalStatus.class, "marital_status", null),
                row.enumeration(EducationLevel.class, "education_level", null),
                row.enumeration(EmploymentStatus.class, "employment_status", null),
                row.date("customer_since"),
                row.enumeration(CustomerSegment.class, "customer_segment", null),
                row.enumeration(KycStatus.class, "kyc_status", KycStatus.PENDING),
                row.enumeration(RiskRating.class, "risk_rating", RiskRating.LOW),
                row.flag("is_politically_exposed", Boolean.FALSE),
                row.enumeration(Channel.class, "preferred_channel", null),
                row.flag("email_verified", null),
                row.flag("phone_verified", null),
                row.integer("num_complaints_last_year"));
    }

    static AccountPayload toAccount(CsvSupport.Row row) {
        return new AccountPayload(
                row.any("account_id", "accountId"),
                row.any("customer_id", "customerId"),
                row.enumeration(AccountType.class, "account_type", null),
                row.enumeration(AccountStatus.class, "account_status", AccountStatus.ACTIVE),
                row.upper("currency"),
                row.date("open_date"),
                row.date("close_date"),
                row.enumeration(RiskRating.class, "risk_rating", null),
                row.get("branch_code"),
                row.get("branch_city"),
                row.decimal("current_balance"),
                row.decimal("avg_monthly_balance_6m"),
                row.decimal("credit_limit"),
                row.decimal("credit_utilization_pct"),
                row.flag("overdraft_enabled", null),
                row.enumeration(CardType.class, "card_type", null),
                row.flag("is_joint_account", null),
                row.integer("num_linked_devices"),
                row.flag("mobile_banking_enrolled", null),
                row.date("last_login_date"),
                row.integer("avg_monthly_txn_count"),
                row.enumeration(AccountTier.class, "account_tier", null));
    }

    static TransactionPayload toTransaction(CsvSupport.Row row) {
        TransactionType type = row.enumeration(TransactionType.class, "transaction_type", null);
        return new TransactionPayload(
                row.any("transaction_id", "external_txn_id", "txn_id"),
                row.any("account_id", "accountId"),
                row.any("customer_id", "customerId"),
                direction(row, type),
                type,
                row.enumeration(Channel.class, "channel", null),
                row.decimal("amount"),
                row.upper("currency"),
                row.any("transaction_timestamp", "txn_timestamp", "timestamp") == null
                        ? null
                        : row.instant(timestampColumn(row)),
                row.date("value_date"),
                row.enumeration(TransactionStatus.class, "status", TransactionStatus.POSTED),
                row.any("narration", "description"),
                row.decimal("balance_after"),
                row.get("counterparty_name"),
                row.get("counterparty_account"),
                row.get("counterparty_bank"),
                row.get("counterparty_bank_code"),
                row.upper("counterparty_country"),
                row.upper("origin_country"),
                row.upper("destination_country"),
                row.flag("is_cross_border", null),
                row.get("merchant_category_code"),
                row.get("branch_code"),
                row.get("device_id"),
                row.get("ip_address"));
    }

    private static String timestampColumn(CsvSupport.Row row) {
        if (row.get("transaction_timestamp") != null) {
            return "transaction_timestamp";
        }
        return row.get("txn_timestamp") != null ? "txn_timestamp" : "timestamp";
    }

    /**
     * Derives the direction when the feed omits it.
     *
     * <p>A credit and a debit of the same size mean opposite things to the layering rule, so the
     * direction is inferred from the transaction type - and, failing that, from the sign of the
     * amount - rather than defaulting to one of them.
     */
    private static TransactionDirection direction(CsvSupport.Row row, TransactionType type) {
        TransactionDirection declared =
                row.enumeration(TransactionDirection.class, "direction", null);
        if (declared != null) {
            return declared;
        }
        if (type != null && type.isInflow()) {
            return TransactionDirection.CREDIT;
        }
        if (type != null && type.isOutflow()) {
            return TransactionDirection.DEBIT;
        }
        BigDecimal amount = row.decimal("amount");
        if (amount != null && amount.signum() < 0) {
            return TransactionDirection.DEBIT;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private <T> Mapped<T> map(
            MultipartFile file, Function<CsvSupport.Row, T> mapper, String identifierColumn) {
        if (file == null || file.isEmpty()) {
            throw new IngestionException("No file was supplied, or the file is empty");
        }
        List<CsvSupport.Row> rows;
        try (InputStream stream = file.getInputStream()) {
            rows = CsvSupport.read(stream);
        } catch (IOException ex) {
            throw new IngestionException("The uploaded file could not be read: " + ex.getMessage());
        }

        List<T> payloads = new ArrayList<>(rows.size());
        List<IngestionResult.RecordError> errors = new ArrayList<>();
        for (CsvSupport.Row row : rows) {
            try {
                payloads.add(mapper.apply(row));
            } catch (RuntimeException ex) {
                errors.add(
                        new IngestionResult.RecordError(
                                (int) row.lineNumber(),
                                row.get(identifierColumn),
                                null,
                                "MALFORMED_ROW",
                                ex.getMessage()));
            }
        }
        if (!errors.isEmpty()) {
            log.warn(
                    "{} of {} rows in {} could not be mapped and were rejected",
                    errors.size(),
                    rows.size(),
                    file.getOriginalFilename());
        }
        return new Mapped<>(payloads, errors, rows.size());
    }

    /** Runs the delegate over the mappable rows and folds the mapping failures back in. */
    private <T> IngestionResult merge(
            Mapped<T> mapped,
            IngestionEntityType entityType,
            Function<List<T>, IngestionResult> delegate) {
        if (mapped.payloads().isEmpty()) {
            // Nothing survived mapping; report the failures rather than opening an empty batch.
            return new IngestionResult(
                    null,
                    entityType,
                    IngestionStatus.FAILED,
                    mapped.totalRows(),
                    0,
                    mapped.errors().size(),
                    0,
                    0L,
                    0L,
                    java.time.Instant.now(),
                    java.time.Instant.now(),
                    mapped.errors());
        }
        IngestionResult result = delegate.apply(mapped.payloads());
        if (mapped.errors().isEmpty()) {
            return result;
        }
        List<IngestionResult.RecordError> allErrors = new ArrayList<>(mapped.errors());
        allErrors.addAll(result.errors());
        return new IngestionResult(
                result.batchReference(),
                result.entityType(),
                result.succeeded() == 0
                        ? IngestionStatus.FAILED
                        : IngestionStatus.COMPLETED_WITH_ERRORS,
                mapped.totalRows(),
                result.succeeded(),
                allErrors.size(),
                result.duplicates(),
                result.alertsCreated(),
                result.alertsAggregated(),
                result.startedAt(),
                result.completedAt(),
                allErrors);
    }

    private record Mapped<T>(
            List<T> payloads, List<IngestionResult.RecordError> errors, int totalRows) {}
}
