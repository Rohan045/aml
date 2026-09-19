package com.azentio.aml.repository;

import com.azentio.aml.domain.Customer;
import com.azentio.aml.domain.enums.KycStatus;
import com.azentio.aml.domain.enums.RiskRating;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Customer master data. The primary key is the source-system identifier from the CSV feed. */
public interface CustomerRepository extends JpaRepository<Customer, String> {

    Optional<Customer> findByNationalId(String nationalId);

    Page<Customer> findByRiskRating(RiskRating riskRating, Pageable pageable);

    Page<Customer> findByKycStatus(KycStatus kycStatus, Pageable pageable);

    List<Customer> findByPoliticallyExposedTrue();

    /** Bulk existence check so a CSV batch can validate foreign keys in one round trip. */
    @Query("select c.customerId from Customer c where c.customerId in :customerIds")
    List<String> findExistingIds(@Param("customerIds") Collection<String> customerIds);

    long countByRiskRating(RiskRating riskRating);
}
