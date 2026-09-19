package com.azentio.aml.web.dto;

import com.azentio.aml.domain.AuditEvent;
import com.azentio.aml.domain.enums.AuditAction;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * One immutable entry in the audit trail.
 *
 * <p>Exposed read-only: there is no endpoint that writes or amends an audit event, because the
 * value of the trail is precisely that the application cannot rewrite it.
 */
@Schema(name = "AuditEventView", description = "An immutable audit trail entry")
public record AuditEventView(
        Long id,
        @Schema(example = "Alert") String entityType,
        @Schema(example = "ALT-2026-000123") String entityId,
        AuditAction action,
        String fromState,
        String toState,
        @Schema(description = "Identity that performed the action") String actor,
        String actorRole,
        Instant occurredAt,
        @Schema(description = "Action-specific context, as JSON") String details) {

    public static AuditEventView from(AuditEvent event) {
        return new AuditEventView(
                event.getId(),
                event.getEntityType(),
                event.getEntityId(),
                event.getAction(),
                event.getFromState(),
                event.getToState(),
                event.getActor(),
                event.getActorRole(),
                event.getOccurredAt(),
                event.getDetails());
    }
}
