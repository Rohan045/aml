package com.azentio.aml.repository;

import com.azentio.aml.domain.IngestionError;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Quarantine for records a batch could not accept.
 *
 * <p>A malformed row fails that row and nothing else. The raw record is retained so the failure is
 * diagnosable and the row can be corrected and resubmitted.
 */
public interface IngestionErrorRepository extends JpaRepository<IngestionError, Long> {

    Page<IngestionError> findByBatch_IdOrderByRecordNumberAsc(Long batchId, Pageable pageable);

    long countByBatch_Id(Long batchId);
}
