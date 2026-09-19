package com.azentio.aml.domain.enums;

/**
 * RBAC roles enforced at the API layer. {@link #COMPLIANCE_OFFICER} and {@link #ADMIN} may view
 * unmasked PII in detail views; everyone else sees masked values.
 */
public enum UserRole {
    ANALYST(false),
    SENIOR_ANALYST(true),
    COMPLIANCE_OFFICER(true),
    AUDITOR(false),
    ADMIN(true);

    private final boolean canViewFullPii;

    UserRole(boolean canViewFullPii) {
        this.canViewFullPii = canViewFullPii;
    }

    public boolean canViewFullPii() {
        return canViewFullPii;
    }

    /** Spring Security authority name, e.g. {@code ROLE_ANALYST}. */
    public String authority() {
        return "ROLE_" + name();
    }
}
