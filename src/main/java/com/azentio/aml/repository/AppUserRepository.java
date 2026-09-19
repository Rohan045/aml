package com.azentio.aml.repository;

import com.azentio.aml.domain.AppUser;
import com.azentio.aml.domain.enums.UserRole;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Application users and their RBAC role. Backs authentication and authorisation. */
public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByUsername(String username);

    Optional<AppUser> findByUsernameAndEnabledTrue(String username);

    List<AppUser> findByRole(UserRole role);

    boolean existsByUsername(String username);
}
