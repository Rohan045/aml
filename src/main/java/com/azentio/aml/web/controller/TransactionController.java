package com.azentio.aml.web.controller;

import com.azentio.aml.service.CustomerService;
import com.azentio.aml.web.dto.TransactionView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lookup of a single transaction by its source-system identifier.
 *
 * <p>Alert evidence cites external transaction ids, so this is the endpoint that turns a piece of
 * evidence into the record behind it. Listing is deliberately not offered here: transactions are
 * only ever meaningful in the context of an account or a customer, and both of those have their
 * own bounded, index-backed timeline endpoints.
 */
@RestController
@RequestMapping("/api/v1/transactions")
@Validated
@Tag(name = "Transactions", description = "Single transaction lookup")
public class TransactionController {

    private final CustomerService customerService;

    public TransactionController(CustomerService customerService) {
        this.customerService = customerService;
    }

    @GetMapping("/{externalTxnId}")
    @Operation(
            summary = "Look up a transaction by its source-system id",
            description = "The identifier cited in alert evidence.")
    public TransactionView byExternalId(
            @Parameter(example = "TXN_0000042") @PathVariable String externalTxnId) {
        return TransactionView.from(customerService.requireTransaction(externalTxnId));
    }
}
