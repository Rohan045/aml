package com.azentio.aml.security;

import com.azentio.aml.repository.AppUserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Loads analyst accounts from the database rather than from in-memory or hardcoded credentials. */
@Service
public class SentinelUserDetailsService implements UserDetailsService {

    private final AppUserRepository appUserRepository;

    public SentinelUserDetailsService(AppUserRepository appUserRepository) {
        this.appUserRepository = appUserRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) {
        return appUserRepository
                .findByUsername(username)
                .map(SentinelUserDetails::new)
                .orElseThrow(
                        () ->
                                new UsernameNotFoundException(
                                        "No account exists for the supplied credentials"));
    }
}
