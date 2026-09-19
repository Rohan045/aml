package com.azentio.aml.service;

import com.azentio.aml.domain.AuditEvent;
import com.azentio.aml.domain.enums.AuditAction;
import com.azentio.aml.repository.AuditEventRepository;
import com.azentio.aml.security.CurrentUser;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Immutable audit trail for every alert and case state transition (regulatory requirement).
 *
 * <p>Writes run in {@link Propagation#REQUIRES_NEW} so an audit record survives even when the
 * business transaction that triggered it is later rolled back: the attempt itself is the fact the
 * regulator cares about. For the same reason a failure to write the audit row is logged loudly but
 * never propagated, since losing the alert would be worse than losing one trail entry.
 *
 * <p>There is deliberately no update or delete path on this service.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditEventRepository auditEventRepository;

    public AuditService(AuditEventRepository auditEventRepository) {
        this.auditEventRepository = auditEventRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(
            String entityType,
            String entityId,
            AuditAction action,
            String fromState,
            String toState,
            String details) {
        try {
            AuditEvent event =
                    AuditEvent.builder()
                            .entityType(entityType)
                            .entityId(entityId)
                            .action(action)
                            .fromState(fromState)
                            .toState(toState)
                            .actor(CurrentUser.username())
                            .actorRole(CurrentUser.role().map(Enum::name).orElse("SYSTEM"))
                            .occurredAt(Instant.now())
                            .details(details)
                            .build();
            auditEventRepository.save(event);
            log.debug(
                    "Audit: {} {} {} by {}", entityType, entityId, action, event.getActor());
        } catch (RuntimeException ex) {
            log.error(
                    "Failed to persist audit event {} for {} {}", action, entityType, entityId, ex);
        }
    }

    public void record(String entityType, String entityId, AuditAction action, String details) {
        record(entityType, entityId, action, null, null, details);
    }

    /** Convenience for the common "X moved from A to B" transition. */
    public void recordTransition(
            String entityType,
            String entityId,
            AuditAction action,
            Enum<?> fromState,
            Enum<?> toState,
            String details) {
        record(
                entityType,
                entityId,
                action,
                fromState == null ? null : fromState.name(),
                toState == null ? null : toState.name(),
                details);
    }

    /** Builds a small JSON detail payload without dragging in a serialiser for flat maps. */
    public static String detailsOf(Object... keyValuePairs) {
        if (keyValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException("Audit details require key/value pairs");
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            if (keyValuePairs[i + 1] != null) {
                values.put(String.valueOf(keyValuePairs[i]), String.valueOf(keyValuePairs[i + 1]));
            }
        }
        StringBuilder json = new StringBuilder("{");
        values.forEach(
                (key, value) -> {
                    if (json.length() > 1) {
                        json.append(',');
                    }
                    json.append('"').append(escape(key)).append("\":\"").append(escape(value))
                            .append('"');
                });
        return json.append('}').toString();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }
}
