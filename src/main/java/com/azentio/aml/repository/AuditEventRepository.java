package com.azentio.aml.repository;

import com.azentio.aml.domain.AuditEvent;
import com.azentio.aml.domain.enums.AuditAction;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Immutable audit trail.
 *
 * <p>Read and insert only. The entity is mapped immutable and no update or delete method is
 * exposed here, so there is no code path by which the evidential record can be rewritten.
 */
public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {

    List<AuditEvent> findByEntityTypeAndEntityIdOrderByOccurredAtAsc(
            String entityType, String entityId);

    Page<AuditEvent> findByActorOrderByOccurredAtDesc(String actor, Pageable pageable);

    Page<AuditEvent> findByActionOrderByOccurredAtDesc(AuditAction action, Pageable pageable);

    Page<AuditEvent> findByOccurredAtBetweenOrderByOccurredAtDesc(
            Instant from, Instant to, Pageable pageable);
}
