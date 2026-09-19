package com.azentio.aml.web.controller;

import com.azentio.aml.service.CustomerService;
import com.azentio.aml.web.dto.AccountView;
import com.azentio.aml.web.dto.TransactionView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Account-level views.
 *
 * <p>Separate from the customer endpoints because an investigation frequently starts from an
 * account number found in an alert rather than from a known subject, and forcing the analyst to
 * resolve the owner first would add a round trip to the most common navigation in the product.
 */
@RestController
@RequestMapping("/api/v1/accounts")
@Validated
@Tag(name = "Accounts", description = "Account master data and per-account transaction history")
public class AccountController {

    private final CustomerService customerService;

    public AccountController(CustomerService customerService) {
        this.customerService = customerService;
    }

    @GetMapping("/{accountId}")
    @Operation(summary = "Account detail")
    public AccountView detail(@PathVariable String accountId) {
        return AccountView.from(customerService.requireAccount(accountId));
    }

    @GetMapping("/{accountId}/transactions")
    @Operation(
            summary = "Account transaction history",
            description =
                    "Chronological, so a deposit followed by an outward transfer reads as the"
                            + " layering pattern it may be. Defaults to the last 90 days.")
    public List<TransactionView> transactions(
            @PathVariable String accountId,
            @Parameter(description = "Window start, ISO-8601; 90 days ago when omitted")
                    @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    Instant from,
            @Parameter(description = "Window end, exclusive; now when omitted")
                    @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    Instant to) {
        return customerService.accountTimeline(accountId, from, to).stream()
                .map(TransactionView::from)
                .toList();
    }
}
