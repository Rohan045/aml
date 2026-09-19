package com.azentio.aml.service.ingestion;

import com.azentio.aml.common.exception.NotFoundException;
import com.azentio.aml.domain.IngestionBatch;
import com.azentio.aml.domain.IngestionError;
import com.azentio.aml.domain.enums.IngestionEntityType;
import com.azentio.aml.repository.IngestionBatchRepository;
import com.azentio.aml.repository.IngestionErrorRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read access to what previous loads did.
 *
 * <p>The counters alone are not enough to operate the platform: when a file is partially rejected
 * the operator needs the individual failures, with the row number and the original record, to fix
 * and resubmit precisely those rows. That is what {@link #errorsOf} provides.
 */
@Service
public class IngestionHistoryService {

    private final IngestionBatchRepository batchRepository;
    private final IngestionErrorRepository errorRepository;

    public IngestionHistoryService(
            IngestionBatchRepository batchRepository, IngestionErrorRepository errorRepository) {
        this.batchRepository = batchRepository;
        this.errorRepository = errorRepository;
    }

    /** Batch history, most recent first, optionally narrowed to one feed. */
    @Transactional(readOnly = true)
    public Page<IngestionBatch> history(IngestionEntityType entityType, Pageable pageable) {
        if (entityType != null) {
            return batchRepository.findByEntityTypeOrderByStartedAtDesc(entityType, pageable);
        }
        Pageable sorted =
                org.springframework.data.domain.PageRequest.of(
                        pageable.getPageNumber(),
                        pageable.getPageSize(),
                        Sort.by(Sort.Direction.DESC, "startedAt"));
        return batchRepository.findAll(sorted);
    }

    @Transactional(readOnly = true)
    public IngestionBatch require(String batchReference) {
        return batchRepository
                .findByBatchReference(batchReference)
                .orElseThrow(() -> NotFoundException.of("IngestionBatch", batchReference));
    }

    /** The rejected rows of a batch, in file order. */
    @Transactional(readOnly = true)
    public Page<IngestionError> errorsOf(String batchReference, Pageable pageable) {
        IngestionBatch batch = require(batchReference);
        return errorRepository.findByBatch_IdOrderByRecordNumberAsc(batch.getId(), pageable);
    }
}
