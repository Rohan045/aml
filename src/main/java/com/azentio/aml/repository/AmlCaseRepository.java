package com.azentio.aml.repository;

import com.azentio.aml.domain.AmlCase;
import com.azentio.aml.domain.enums.CaseStatus;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Investigation cases: the container that groups related alerts for a single customer. */
public interface AmlCaseRepository extends JpaRepository<AmlCase, Long> {

    /**
     * The customer is fetched eagerly on every read that reaches a DTO.
     *
     * <p>Both the case queue and the case detail render the customer's name - masked or not - and
     * with {@code open-in-view=false} the persistence context is gone before the controller maps
     * the result, so the association has to be resolved here or not at all.
     */
    @EntityGraph(attributePaths = "customer")
    Optional<AmlCase> findByCaseNumber(String caseNumber);

    @EntityGraph(attributePaths = "customer")
    Page<AmlCase> findByStatusIn(Collection<CaseStatus> statuses, Pageable pageable);

    @EntityGraph(attributePaths = "customer")
    Page<AmlCase> findByAssignedToAndStatusIn(
            String assignedTo, Collection<CaseStatus> statuses, Pageable pageable);

    @EntityGraph(attributePaths = "customer")
    Page<AmlCase> findByCustomer_CustomerIdOrderByOpenedAtDesc(
            String customerId, Pageable pageable);

    /**
     * Case lookup by id with the customer attached.
     *
     * <p>Plain {@code findById} leaves the customer as a proxy, which is fine inside a service
     * transaction but fails the moment the case detail projection asks for the customer's name.
     */
    @EntityGraph(attributePaths = "customer")
    Optional<AmlCase> findWithCustomerById(Long id);

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
