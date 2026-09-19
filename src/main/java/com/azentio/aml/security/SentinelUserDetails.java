package com.azentio.aml.security;

import com.azentio.aml.domain.AppUser;
import com.azentio.aml.domain.enums.UserRole;
import java.util.Collection;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * Authenticated principal that carries the domain role alongside the Spring Security authority, so
 * PII-masking decisions can be made from the role itself rather than by string-matching authorities.
 */
public class SentinelUserDetails implements UserDetails {

    private final String username;
    private final String passwordHash;
    private final String fullName;
    private final UserRole role;
    private final boolean enabled;
    private final boolean locked;

    public SentinelUserDetails(AppUser user) {
        this.username = user.getUsername();
        this.passwordHash = user.getPasswordHash();
        this.fullName = user.getFullName();
        this.role = user.getRole();
        this.enabled = Boolean.TRUE.equals(user.getEnabled());
        this.locked = Boolean.TRUE.equals(user.getAccountLocked());
    }

    public UserRole getRole() {
        return role;
    }

    public String getFullName() {
        return fullName;
    }

    /** Drives business rule 8: only privileged roles ever see unmasked PII. */
    public boolean canViewFullPii() {
        return role != null && role.canViewFullPii();
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority(role.authority()));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return !locked;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }
}
