package com.azentio.aml.repository;

import com.azentio.aml.domain.AmlCase;
import com.azentio.aml.domain.enums.CaseStatus;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Investigation cases: the container that groups related alerts for a single customer. */
public interface AmlCaseRepository extends JpaRepository<AmlCase, Long> {

    Optional<AmlCase> findByCaseNumber(String caseNumber);

    Page<AmlCase> findByStatusIn(Collection<CaseStatus> statuses, Pageable pageable);

    Page<AmlCase> findByAssignedToAndStatusIn(
            String assignedTo, Collection<CaseStatus> statuses, Pageable pageable);

    Page<AmlCase> findByCustomer_CustomerIdOrderByOpenedAtDesc(
            String customerId, Pageable pageable);

    /**
     * An existing open case for this customer, so a new alert attaches to the ongoing
     * investigation rather than fragmenting it across several cases.
     */
    @Query(
            """
            select c
              from AmlCase c
             where c.customer.customerId = :customerId
               and c.status <> com.azentio.aml.domain.enums.CaseStatus.CLOSED
             order by c.openedAt desc
            """)
    java.util.List<AmlCase> findOpenCasesForCustomer(@Param("customerId") String customerId);

    long countByStatusIn(Collection<CaseStatus> statuses);

    long countBySarFiledTrue();
}
