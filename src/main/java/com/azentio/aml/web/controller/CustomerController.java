package com.azentio.aml.web.controller;

import com.azentio.aml.domain.Customer;
import com.azentio.aml.domain.enums.AuditAction;
import com.azentio.aml.domain.enums.KycStatus;
import com.azentio.aml.domain.enums.RiskRating;
import com.azentio.aml.security.CurrentUser;
import com.azentio.aml.service.AlertService;
import com.azentio.aml.service.AuditService;
import com.azentio.aml.service.CaseService;
import com.azentio.aml.service.CustomerService;
import com.azentio.aml.web.dto.AccountView;
import com.azentio.aml.web.dto.AlertSummary;
import com.azentio.aml.web.dto.CaseSummary;
import com.azentio.aml.web.dto.CustomerDetail;
import com.azentio.aml.web.dto.CustomerSummary;
import com.azentio.aml.web.dto.PageResponse;
import com.azentio.aml.web.dto.TransactionView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Customer 360: the KYC record, the accounts, the transaction timeline and everything the platform
 * has already flagged about the subject.
 *
 * <p>The PII split required by business rule 8 is enforced here rather than in the UI. The list
 * endpoint masks unconditionally; the detail endpoint reveals only to entitled roles and writes an
 * audit entry when it does, so a bulk harvest of identities through the detail endpoint leaves an
 * obvious trail.
 */
@RestController
@RequestMapping("/api/v1/customers")
@Validated
@Tag(name = "Customers", description = "Customer KYC, accounts and transaction timeline")
public class CustomerController {

    private final CustomerService customerService;
    private final AlertService alertService;
    private final CaseService caseService;
    private final AuditService auditService;

    public CustomerController(
            CustomerService customerService,
            AlertService alertService,
            CaseService caseService,
            AuditService auditService) {
        this.customerService = customerService;
        this.alertService = alertService;
        this.caseService = caseService;
        this.auditService = auditService;
    }

    @GetMapping
    @Operation(
            summary = "Customer list",
            description = "Names are always masked in this view, for every role.")
    public PageResponse<CustomerSummary> list(
            @RequestParam(required = false) RiskRating riskRating,
            @RequestParam(required = false) KycStatus kycStatus,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("customerId"));
        return PageResponse.of(
                customerService.list(riskRating, kycStatus, pageable), CustomerSummary::from);
    }

    @GetMapping("/politically-exposed")
    @Operation(
            summary = "Politically exposed persons",
            description = "PEP status is an aggravating factor in every risk score.")
    public List<CustomerSummary> politicallyExposed() {
        return customerService.politicallyExposed().stream().map(CustomerSummary::from).toList();
    }

    @GetMapping("/{customerId}")
    @Operation(
            summary = "Customer detail",
            description =
                    "Identifying fields are returned in the clear only to roles entitled to see"
                            + " them; the response reports which happened in 'piiUnmasked'. A"
                            + " disclosure is written to the audit trail.")
    public CustomerDetail detail(@PathVariable String customerId) {
        Customer customer = customerService.require(customerId);
        boolean unmasked = CurrentUser.canViewFullPii();
        if (unmasked) {
            auditService.record(
                    "Customer",
                    customerId,
                    AuditAction.PII_REVEALED,
                    AuditService.detailsOf("context", "customer-detail"));
        }
        List<AccountView> accounts =
                customerService.accountsOf(customerId).stream().map(AccountView::from).toList();
        return CustomerDetail.from(customer, accounts, unmasked);
    }

    @GetMapping("/{customerId}/accounts")
    @Operation(summary = "Accounts held by a customer")
    public List<AccountView> accounts(@PathVariable String customerId) {
        return customerService.accountsOf(customerId).stream().map(AccountView::from).toList();
    }

    @GetMapping("/{customerId}/transactions")
    @Operation(
            summary = "Customer transaction timeline",
            description =
                    "Chronological movements across every account the customer holds. Defaults to"
                            + " the last 90 days; the window is capped at 2 years.")
    public List<TransactionView> timeline(
            @PathVariable String customerId,
            @Parameter(description = "Window start, ISO-8601; 90 days ago when omitted")
                    @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    Instant from,
            @Parameter(description = "Window end, exclusive; now when omitted")
                    @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    Instant to) {
        return customerService.customerTimeline(customerId, from, to).stream()
                .map(TransactionView::from)
                .toList();
    }

    @GetMapping("/{customerId}/alerts")
    @Operation(summary = "Alerts raised against a customer, most recent first")
    public PageResponse<AlertSummary> alerts(
            @PathVariable String customerId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int size) {
        return PageResponse.of(
                alertService.forCustomer(customerId, PageRequest.of(page, size)),
                AlertSummary::from);
    }

    @GetMapping("/{customerId}/cases")
    @Operation(summary = "Investigations opened on a customer")
    public PageResponse<CaseSummary> cases(
            @PathVariable String customerId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int size) {
        return PageResponse.of(
                caseService.forCustomer(customerId, PageRequest.of(page, size)),
                CaseSummary::from);
    }
}
