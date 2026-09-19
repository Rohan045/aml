package com.azentio.aml.repository;

import com.azentio.aml.domain.AlertEvidence;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** The transactions cited as evidence for an alert. */
public interface AlertEvidenceRepository extends JpaRepository<AlertEvidence, Long> {

    List<AlertEvidence> findByAlert_IdOrderByOccurredAtAsc(Long alertId);

    List<AlertEvidence> findByTransaction_Id(Long transactionId);
}
