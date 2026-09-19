package com.azentio.aml.domain;

import com.azentio.aml.domain.enums.UserRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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

/**
 * Platform operator (analyst, compliance officer, auditor, admin) used for RBAC and for stamping
 * the actor identity onto every alert and case transition.
 *
 * <p>Only the BCrypt hash is stored - never a plaintext password - and it is excluded from
 * {@code toString()} so it cannot leak into logs.
 */
@Entity
@Table(
        name = "app_users",
        uniqueConstraints = {
            @UniqueConstraint(name = "uk_user_username", columnNames = "username"),
            @UniqueConstraint(name = "uk_user_email", columnNames = "email")
        },
        indexes = @Index(name = "idx_user_role", columnList = "role"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(onlyExplicitlyIncluded = true)
public class AppUser extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @NotBlank
    @Size(max = 100)
    @Column(name = "username", length = 100, nullable = false, updatable = false)
    @ToString.Include
    private String username;

    /** BCrypt hash only. */
    @NotBlank
    @Size(max = 100)
    @Column(name = "password_hash", length = 100, nullable = false)
    private String passwordHash;

    @NotBlank
    @Size(max = 150)
    @Column(name = "full_name", length = 150, nullable = false)
    private String fullName;

    @Email
    @Size(max = 160)
    @Column(name = "email", length = 160)
    private String email;

    @NotNull
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "role", length = 40, nullable = false)
    @ToString.Include
    private UserRole role;

    @NotNull
    @Column(name = "enabled", nullable = false)
    @Builder.Default
    private Boolean enabled = Boolean.TRUE;

    @NotNull
    @Column(name = "account_locked", nullable = false)
    @Builder.Default
    private Boolean accountLocked = Boolean.FALSE;

    @Column(name = "failed_login_attempts")
    @Builder.Default
    private Integer failedLoginAttempts = 0;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "password_changed_at")
    private Instant passwordChangedAt;

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AppUser that)) {
            return false;
        }
        return username != null && username.equals(that.username);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(username);
    }
}
