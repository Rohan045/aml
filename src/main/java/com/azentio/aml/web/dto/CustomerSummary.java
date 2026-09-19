package com.azentio.aml.web.dto;

import com.azentio.aml.domain.Customer;
import com.azentio.aml.domain.enums.CustomerSegment;
import com.azentio.aml.domain.enums.KycStatus;
import com.azentio.aml.domain.enums.RiskRating;
import com.azentio.aml.security.PiiMasker;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Customer as shown in list views.
 *
 * <p>Business rule 8: the name is always masked here regardless of the caller's role, because a
 * list view is a bulk disclosure surface - an entitled analyst can still open the detail view for
 * the one customer they are actually investigating, and that access is audited.
 */
@Schema(name = "CustomerSummary", description = "Customer list entry with PII masked")
public record CustomerSummary(
        @Schema(example = "CUST_00001") String customerId,
        @Schema(example = "K****** S*****") String name,
        RiskRating riskRating,
        KycStatus kycStatus,
        CustomerSegment segment,
        @Schema(example = "IN") String country,
        boolean politicallyExposed) {

    public static CustomerSummary from(Customer customer) {
        return new CustomerSummary(
                customer.getCustomerId(),
                PiiMasker.maskName(customer.getFullName()),
                customer.getRiskRating(),
                customer.getKycStatus(),
                customer.getCustomerSegment(),
                customer.getCountry(),
                Boolean.TRUE.equals(customer.getPoliticallyExposed()));
    }
}
