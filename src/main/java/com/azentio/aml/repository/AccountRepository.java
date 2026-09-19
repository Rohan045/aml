package com.azentio.aml.repository;

import com.azentio.aml.domain.Account;
import com.azentio.aml.domain.enums.AccountStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Account master data, keyed by the source-system account identifier. */
public interface AccountRepository extends JpaRepository<Account, String> {

    List<Account> findByCustomer_CustomerId(String customerId);

    Page<Account> findByAccountStatus(AccountStatus accountStatus, Pageable pageable);

    /** Resolves the owning customer without loading the account, for ingestion enrichment. */
    @Query("select a.customer.customerId from Account a where a.accountId = :accountId")
    Optional<String> findCustomerIdByAccountId(@Param("accountId") String accountId);

    @Query("select a.accountId from Account a where a.accountId in :accountIds")
    List<String> findExistingIds(@Param("accountIds") Collection<String> accountIds);

    long countByAccountStatus(AccountStatus accountStatus);
}
