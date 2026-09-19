package com.azentio.aml.service.ingestion;

import com.azentio.aml.domain.Account;
import com.azentio.aml.domain.Customer;
import com.azentio.aml.domain.IngestionBatch;
import com.azentio.aml.domain.IngestionError;
import com.azentio.aml.domain.Transaction;
import com.azentio.aml.domain.enums.IngestionEntityType;
import com.azentio.aml.domain.enums.IngestionSource;
import com.azentio.aml.domain.enums.IngestionStatus;
import com.azentio.aml.repository.AccountRepository;
import com.azentio.aml.repository.CustomerRepository;
import com.azentio.aml.repository.IngestionBatchRepository;
import com.azentio.aml.repository.TransactionRepository;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional write side of ingestion.
 *
 * <p>Split out of {@link TransactionIngestionService} because Spring's {@code @Transactional} is
 * proxy-based: a service calling its own annotated methods would silently run them in the caller's
 * transaction. Keeping the writes in a separate bean guarantees the intended boundaries - one
 * transaction per chunk, and a batch record that survives independently of the chunk that failed.
 */
@Component
class IngestionWriter {

    private final IngestionBatchRepository batchRepository;
    private final TransactionRepository transactionRepository;
    private final CustomerRepository customerRepository;
    private final AccountRepository accountRepository;

    IngestionWriter(
            IngestionBatchRepository batchRepository,
            TransactionRepository transactionRepository,
            CustomerRepository customerRepository,
            AccountRepository accountRepository) {
        this.batchRepository = batchRepository;
        this.transactionRepository = transactionRepository;
        this.customerRepository = customerRepository;
        this.accountRepository = accountRepository;
    }

    /**
     * Opens a batch in its own transaction so the audit trail of "an ingestion was attempted"
     * exists even if every subsequent chunk rolls back.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    IngestionBatch openBatch(
            IngestionSource source,
            IngestionEntityType entityType,
            String fileName,
            String submittedBy,
            int totalRecords) {
        IngestionBatch batch =
                IngestionBatch.builder()
                        .batchReference(nextBatchReference())
                        .source(source)
                        .entityType(entityType)
                        .fileName(fileName)
                        .submittedBy(submittedBy)
                        .status(IngestionStatus.PROCESSING)
                        .totalRecords(totalRecords)
                        .startedAt(Instant.now())
                        .build();
        return batchRepository.save(batch);
    }

    /**
     * Persists one chunk. Each chunk commits on its own so a large file makes steady, visible
     * progress and a failure late in the file does not discard everything ingested before it.
     */
    @Transactional
    List<Transaction> saveTransactions(List<Transaction> chunk) {
        return transactionRepository.saveAll(chunk);
    }

    @Transactional
    List<Customer> saveCustomers(List<Customer> chunk) {
        return customerRepository.saveAll(chunk);
    }

    @Transactional
    List<Account> saveAccounts(List<Account> chunk) {
        return accountRepository.saveAll(chunk);
    }

    /** Writes the final counters and the rejected records in one transaction. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    IngestionBatch closeBatch(
            Long batchId,
            IngestionStatus status,
            int succeeded,
            int failed,
            int duplicates,
            String errorSummary,
            List<IngestionResult.RecordError> errors,
            String rawSample) {
        IngestionBatch batch = batchRepository.findById(batchId).orElseThrow();
        batch.setStatus(status);
        batch.setSuccessCount(succeeded);
        batch.setFailureCount(failed);
        batch.setDuplicateCount(duplicates);
        batch.setErrorSummary(errorSummary);
        batch.setCompletedAt(Instant.now());
        for (IngestionResult.RecordError error : errors) {
            batch.addError(
                    IngestionError.builder()
                            .recordNumber(error.recordNumber())
                            .recordIdentifier(error.recordIdentifier())
                            .fieldName(error.fieldName())
                            .errorCode(error.errorCode())
                            .errorMessage(error.errorMessage())
                            .rawRecord(rawSample)
                            .occurredAt(Instant.now())
                            .build());
        }
        return batchRepository.save(batch);
    }

    /**
     * Time-ordered, collision-resistant reference. Avoids a database sequence because ingestion is
     * explicitly concurrent and a shared sequence is the one thing every writer would contend on.
     */
    private String nextBatchReference() {
        Instant now = Instant.now();
        return "BATCH-"
                + now.atZone(ZoneOffset.UTC).getYear()
                + "-"
                + Long.toString(now.toEpochMilli(), 36).toUpperCase()
                + ThreadLocalRandom.current().nextInt(10, 100);
    }
}
