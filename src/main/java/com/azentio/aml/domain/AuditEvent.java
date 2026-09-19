package com.azentio.aml.domain;

import com.azentio.aml.domain.enums.AuditAction;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.hibernate.annotations.Immutable;

/**
 * Immutable audit record of every alert, case and rule state transition, capturing what changed,
 * when, and by whom. Rows are insert-only: there are no update paths and the table must never be
 * exposed through a delete endpoint.
 */
@Entity
@Immutable
@Table(
        name = "audit_events",
        indexes = {
            @Index(name = "idx_audit_entity", columnList = "entity_type,entity_id"),
            @Index(name = "idx_audit_actor", columnList = "actor"),
            @Index(name = "idx_audit_occurred_at", columnList = "occurred_at"),
            @Index(name = "idx_audit_action", columnList = "action")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(onlyExplicitlyIncluded = true)
public class AuditEvent implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** Logical entity name, e.g. {@code ALERT}, {@code CASE}, {@code RULE_CONFIG}. */
    @NotBlank
    @Size(max = 40)
    @Column(name = "entity_type", length = 40, nullable = false, updatable = false)
    @ToString.Include
    private String entityType;

    @NotBlank
    @Size(max = 64)
    @Column(name = "entity_id", length = 64, nullable = false, updatable = false)
    @ToString.Include
    private String entityId;

    @NotNull
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "action", length = 40, nullable = false, updatable = false)
    @ToString.Include
    private AuditAction action;

    @Size(max = 40)
    @Column(name = "from_state", length = 40, updatable = false)
    private String fromState;

    @Size(max = 40)
    @Column(name = "to_state", length = 40, updatable = false)
    private String toState;

    /** Authenticated username responsible for the change; {@code SYSTEM} for engine actions. */
    @NotBlank
    @Size(max = 100)
    @Column(name = "actor", length = 100, nullable = false, updatable = false)
    @ToString.Include
    private String actor;

    @Size(max = 40)
    @Column(name = "actor_role", length = 40, updatable = false)
    private String actorRole;

    @NotNull
    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    /** Free-form JSON payload describing the change detail. */
    @Column(name = "details", columnDefinition = "text", updatable = false)
    private String details;

    @Size(max = 64)
    @Column(name = "correlation_id", length = 64, updatable = false)
    private String correlationId;

    @Size(max = 64)
    @Column(name = "ip_address", length = 64, updatable = false)
    private String ipAddress;

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AuditEvent that)) {
            return false;
        }
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
