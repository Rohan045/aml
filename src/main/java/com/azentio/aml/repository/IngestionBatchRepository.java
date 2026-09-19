package com.azentio.aml.repository;

import com.azentio.aml.domain.IngestionBatch;
import com.azentio.aml.domain.enums.IngestionEntityType;
import com.azentio.aml.domain.enums.IngestionStatus;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** One row per ingestion run, giving every load a traceable outcome. */
public interface IngestionBatchRepository extends JpaRepository<IngestionBatch, Long> {

    Optional<IngestionBatch> findByBatchReference(String batchReference);

    Page<IngestionBatch> findByStatusIn(Collection<IngestionStatus> statuses, Pageable pageable);

    Page<IngestionBatch> findByEntityTypeOrderByStartedAtDesc(
            IngestionEntityType entityType, Pageable pageable);

    /**
     * Detects a re-upload of an identical file.
     *
     * <p>Matching on content checksum rather than file name catches the common operational mistake
     * of loading the same extract twice under a different name.
     */
    Optional<IngestionBatch> findFirstByFileChecksumAndEntityTypeOrderByStartedAtDesc(
            String fileChecksum, IngestionEntityType entityType);
}
