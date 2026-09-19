package com.azentio.aml.security;

import com.azentio.aml.config.JpaAuditingConfig;
import com.azentio.aml.domain.enums.UserRole;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Reads the acting analyst out of the security context for auditing and PII decisions. */
public final class CurrentUser {

    private CurrentUser() {}

    /** Never null: unauthenticated background work is attributed to {@code SYSTEM}. */
    public static String username() {
        return details().map(SentinelUserDetails::getUsername)
                .orElseGet(
                        () ->
                                authentication()
                                        .map(Authentication::getName)
                                        .filter(name -> !"anonymousUser".equals(name))
                                        .orElse(JpaAuditingConfig.SYSTEM_ACTOR));
    }

    public static Optional<UserRole> role() {
        return details().map(SentinelUserDetails::getRole);
    }

    /**
     * Whether the caller may see unmasked customer PII. Background/system processing returns true
     * because it never renders PII to a human; HTTP callers are governed by their role.
     */
    public static boolean canViewFullPii() {
        Optional<SentinelUserDetails> principal = details();
        if (principal.isPresent()) {
            return principal.get().canViewFullPii();
        }
        return authentication().isEmpty();
    }

    private static Optional<SentinelUserDetails> details() {
        return authentication()
                .map(Authentication::getPrincipal)
                .filter(SentinelUserDetails.class::isInstance)
                .map(SentinelUserDetails.class::cast);
    }

    private static Optional<Authentication> authentication() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.empty();
        }
        return Optional.of(authentication);
    }
}
