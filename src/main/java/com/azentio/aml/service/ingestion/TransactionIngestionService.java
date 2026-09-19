package com.azentio.aml.service.ingestion;

import com.azentio.aml.common.exception.IngestionException;
import com.azentio.aml.config.SentinelProperties;
import com.azentio.aml.domain.Account;
import com.azentio.aml.domain.Transaction;
import com.azentio.aml.domain.enums.AuditAction;
import com.azentio.aml.domain.enums.IngestionEntityType;
import com.azentio.aml.domain.enums.IngestionSource;
import com.azentio.aml.domain.enums.IngestionStatus;
import com.azentio.aml.domain.enums.ScreeningStatus;
import com.azentio.aml.domain.enums.TransactionStatus;
import com.azentio.aml.repository.AccountRepository;
import com.azentio.aml.repository.TransactionRepository;
import com.azentio.aml.security.CurrentUser;
import com.azentio.aml.service.AlertService;
import com.azentio.aml.service.AuditService;
import com.azentio.aml.service.CurrencyService;
import com.azentio.aml.detection.DetectionEngine;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Entry point for every transaction that enters the platform.
 *
 * <p>Responsibilities, in the order they are applied to each record:
 *
 * <ol>
 *   <li><b>Validation</b> - bean-validation on the payload, then referential checks the annotations
 *       cannot express (the account must exist).
 *   <li><b>Idempotency</b> - the external transaction id is unique. Replayed files and redelivered
 *       Kafka messages are counted as duplicates and skipped rather than rejected, because at-least
 *       -once delivery is normal and must not produce duplicate alerts.
 *   <li><b>Normalisation</b> - every amount is converted to the base currency so that a single
 *       threshold can be compared across a multi-currency book (business rule 8).
 *   <li><b>Detection</b> - optionally triggered inline, so an alert exists within seconds of the
 *       transaction being booked.
 * </ol>
 *
 * <p>The class holds no mutable state, so several batches (or Kafka consumer threads) can ingest
 * concurrently; all coordination happens through the database's unique constraints.
 */
@Service
public class TransactionIngestionService {

    private static final Logger log = LoggerFactory.getLogger(TransactionIngestionService.class);

    /** Chunk size for the existence pre-check; keeps the generated {@code IN (...)} list sane. */
    private static final int LOOKUP_CHUNK = 500;

    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;
    private final CurrencyService currencyService;
    private final DetectionEngine detectionEngine;
    private final IngestionWriter writer;
    private final AuditService auditService;
    private final Validator validator;
    private final SentinelProperties properties;

    public TransactionIngestionService(
            TransactionRepository transactionRepository,
            AccountRepository accountRepository,
            CurrencyService currencyService,
            DetectionEngine detectionEngine,
            IngestionWriter writer,
            AuditService auditService,
            Validator validator,
            SentinelProperties properties) {
        this.transactionRepository = transactionRepository;
        this.accountRepository = accountRepository;
        this.currencyService = currencyService;
        this.detectionEngine = detectionEngine;
        this.writer = writer;
        this.auditService = auditService;
        this.validator = validator;
        this.properties = properties;
    }

    /**
     * Ingests a single transaction and screens it immediately.
     *
     * <p>This is the real-time path: it deliberately runs detection inline rather than deferring to
     * the sweep, so the caller learns straight away whether the transaction raised an alert.
     */
    public IngestionResult ingestOne(TransactionPayload payload, IngestionSource source) {
        return ingest(List.of(payload), source, null);
    }

    /**
     * Ingests a batch of transactions.
     *
     * @param source where the records came from, recorded on the batch for traceability
     * @param fileName original file name for CSV uploads, {@code null} otherwise
     * @throws IngestionException when the batch is empty or exceeds the configured maximum
     */
    public IngestionResult ingest(
            List<TransactionPayload> payloads, IngestionSource source, String fileName) {
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

        Instant startedAt = Instant.now();
        String actor = CurrentUser.username();
        var batch =
                writer.openBatch(
                        source,
                        IngestionEntityType.TRANSACTION,
                        fileName,
                        actor,
                        payloads.size());
        auditService.record(
                "IngestionBatch",
                batch.getBatchReference(),
                AuditAction.INGESTION_STARTED,
                AuditService.detailsOf(
                        "source", source, "records", payloads.size(), "fileName", fileName));

        List<IngestionResult.RecordError> errors = new ArrayList<>();
        Set<String> known = knownTransactionIds(payloads);
        Map<String, Account> accounts = loadAccounts(payloads);

        List<Transaction> accepted = new ArrayList<>();
        int duplicates = 0;
        int recordNumber = 0;

        for (TransactionPayload payload : payloads) {
            recordNumber++;
            List<IngestionResult.RecordError> violations = validate(payload, recordNumber);
            if (!violations.isEmpty()) {
                errors.addAll(violations);
                continue;
            }
            // Covers both rows already in the database and repeats inside this very file.
            if (!known.add(payload.externalTxnId())) {
                duplicates++;
                continue;
            }
            Account account = accounts.get(payload.accountId());
            if (account == null) {
                errors.add(
                        new IngestionResult.RecordError(
                                recordNumber,
                                payload.externalTxnId(),
                                "accountId",
                                "UNKNOWN_ACCOUNT",
                                "Account " + payload.accountId() + " does not exist"));
                continue;
            }
            try {
                accepted.add(toEntity(payload, account, batch.getId()));
            } catch (IllegalArgumentException ex) {
                errors.add(
                        new IngestionResult.RecordError(
                                recordNumber,
                                payload.externalTxnId(),
                                "currency",
                                "FX_RATE_MISSING",
                                ex.getMessage()));
            }
        }

        List<Transaction> persisted = persist(accepted);

        AlertService.Outcome outcome = AlertService.Outcome.EMPTY;
        if (properties.getIngestion().isDetectOnIngest() && !persisted.isEmpty()) {
            outcome = runDetection(persisted);
        }

        IngestionStatus status =
                errors.isEmpty()
                        ? IngestionStatus.COMPLETED
                        : (persisted.isEmpty()
                                ? IngestionStatus.FAILED
                                : IngestionStatus.COMPLETED_WITH_ERRORS);
        String summary =
                errors.isEmpty()
                        ? null
                        : errors.size() + " record(s) rejected; first: " + errors.get(0).errorMessage();
        var closed =
                writer.closeBatch(
                        batch.getId(),
                        status,
                        persisted.size(),
                        errors.size(),
                        duplicates,
                        summary,
                        errors,
                        null);

        auditService.record(
                "IngestionBatch",
                closed.getBatchReference(),
                AuditAction.INGESTION_COMPLETED,
                AuditService.detailsOf(
                        "status", status,
                        "succeeded", persisted.size(),
                        "failed", errors.size(),
                        "duplicates", duplicates,
                        "alertsCreated", outcome.created()));
        log.info(
                "Batch {} finished: {} stored, {} rejected, {} duplicates, {} alerts in {} ms",
                closed.getBatchReference(),
                persisted.size(),
                errors.size(),
                duplicates,
                outcome.created(),
                Instant.now().toEpochMilli() - startedAt.toEpochMilli());

        return new IngestionResult(
                closed.getBatchReference(),
                IngestionEntityType.TRANSACTION,
                status,
                payloads.size(),
                persisted.size(),
                errors.size(),
                duplicates,
                outcome.created(),
                outcome.aggregated(),
                startedAt,
                closed.getCompletedAt(),
                errors);
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /**
     * Saves in chunks so the Hibernate persistence context never grows to the size of the file -
     * the single biggest cause of ingestion slowdowns at 10k+ records.
     */
    private List<Transaction> persist(List<Transaction> accepted) {
        List<Transaction> persisted = new ArrayList<>(accepted.size());
        int chunkSize = properties.getIngestion().getBatchSize();
        for (int from = 0; from < accepted.size(); from += chunkSize) {
            int to = Math.min(from + chunkSize, accepted.size());
            persisted.addAll(writer.saveTransactions(accepted.subList(from, to)));
        }
        return persisted;
    }

    /**
     * Screens what was just ingested.
     *
     * <p>A single transaction takes the streaming path (cheap, precisely scoped); a batch takes the
     * pending sweep, which evaluates every rule once over the whole window rather than once per
     * record - the difference between one pass and ten thousand.
     */
    private AlertService.Outcome runDetection(List<Transaction> persisted) {
        try {
            if (persisted.size() == 1) {
                return detectionEngine.screen(persisted.get(0));
            }
            return detectionEngine.screenPending();
        } catch (RuntimeException ex) {
            // Ingestion succeeded; detection can be retried by the sweep. Never lose the data.
            log.error("Detection after ingestion failed; records remain PENDING screening", ex);
            return AlertService.Outcome.EMPTY;
        }
    }

    private List<IngestionResult.RecordError> validate(TransactionPayload payload, int recordNumber) {
        List<IngestionResult.RecordError> errors = new ArrayList<>();
        for (ConstraintViolation<TransactionPayload> violation : validator.validate(payload)) {
            errors.add(
                    new IngestionResult.RecordError(
                            recordNumber,
                            payload.externalTxnId(),
                            violation.getPropertyPath().toString(),
                            "VALIDATION_FAILED",
                            violation.getMessage()));
        }
        if (payload.transactionTimestamp() != null
                && payload.transactionTimestamp().isAfter(Instant.now().plusSeconds(86_400))) {
            errors.add(
                    new IngestionResult.RecordError(
                            recordNumber,
                            payload.externalTxnId(),
                            "transactionTimestamp",
                            "FUTURE_DATED",
                            "Transaction timestamp is more than a day in the future"));
        }
        return errors;
    }

    /** One round trip per chunk to find which external ids are already stored. */
    private Set<String> knownTransactionIds(List<TransactionPayload> payloads) {
        List<String> ids =
                payloads.stream()
                        .map(TransactionPayload::externalTxnId)
                        .filter(id -> id != null && !id.isBlank())
                        .distinct()
                        .toList();
        Set<String> known = new HashSet<>();
        for (int from = 0; from < ids.size(); from += LOOKUP_CHUNK) {
            int to = Math.min(from + LOOKUP_CHUNK, ids.size());
            known.addAll(transactionRepository.findExistingExternalTxnIds(ids.subList(from, to)));
        }
        return known;
    }

    /** Resolves every referenced account up front instead of one lookup per record. */
    private Map<String, Account> loadAccounts(List<TransactionPayload> payloads) {
        Set<String> accountIds = new HashSet<>();
        for (TransactionPayload payload : payloads) {
            if (payload.accountId() != null) {
                accountIds.add(payload.accountId());
            }
        }
        Map<String, Account> accounts = new HashMap<>();
        for (Account account : accountRepository.findAllById(accountIds)) {
            accounts.put(account.getAccountId(), account);
        }
        return accounts;
    }

    private Transaction toEntity(TransactionPayload payload, Account account, Long batchId) {
        String currency = payload.currency().toUpperCase(java.util.Locale.ROOT);
        CurrencyService.Conversion conversion =
                currencyService.toBaseCurrency(
                        payload.amount(), currency, payload.effectiveValueDate());

        return Transaction.builder()
                .externalTxnId(payload.externalTxnId())
                .account(account)
                .customer(account.getCustomer())
                .direction(payload.direction())
                .transactionType(payload.transactionType())
                .channel(payload.channel())
                .amount(payload.amount())
                .currency(currency)
                .exchangeRate(conversion.rate())
                .baseAmount(conversion.baseAmount())
                .baseCurrency(conversion.baseCurrency())
                .transactionTimestamp(payload.transactionTimestamp())
                .valueDate(payload.effectiveValueDate())
                .status(payload.status() == null ? TransactionStatus.POSTED : payload.status())
                .narration(payload.narration())
                .balanceAfter(payload.balanceAfter())
                .counterpartyName(payload.counterpartyName())
                .counterpartyAccount(payload.counterpartyAccount())
                .counterpartyBank(payload.counterpartyBank())
                .counterpartyBankCode(payload.counterpartyBankCode())
                .counterpartyCountry(upper(payload.counterpartyCountry()))
                .originCountry(upper(payload.originCountry()))
                .destinationCountry(upper(payload.destinationCountry()))
                .crossBorder(crossBorder(payload))
                .merchantCategoryCode(payload.merchantCategoryCode())
                .branchCode(payload.branchCode())
                .deviceId(payload.deviceId())
                .ipAddress(payload.ipAddress())
                .screeningStatus(ScreeningStatus.PENDING)
                .ingestionBatchId(batchId)
                .build();
    }

    /**
     * Derives the cross-border flag when the feed omits it, so the jurisdiction rule does not
     * depend on upstream systems remembering to set it.
     */
    private Boolean crossBorder(TransactionPayload payload) {
        if (payload.crossBorder() != null) {
            return payload.crossBorder();
        }
        String origin = upper(payload.originCountry());
        String destination = upper(payload.destinationCountry());
        if (origin != null && destination != null) {
            return !origin.equals(destination);
        }
        return Boolean.FALSE;
    }

    private String upper(String value) {
        return value == null || value.isBlank()
                ? null
                : value.trim().toUpperCase(java.util.Locale.ROOT);
    }

    /** Exposed for the dashboard: total transactions currently stored. */
    public long storedTransactionCount() {
        return transactionRepository.count();
    }
}
