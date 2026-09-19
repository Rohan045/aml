package com.azentio.aml.web.dto;

import com.azentio.aml.domain.Customer;
import com.azentio.aml.domain.enums.Channel;
import com.azentio.aml.domain.enums.CustomerSegment;
import com.azentio.aml.domain.enums.EmploymentStatus;
import com.azentio.aml.domain.enums.Gender;
import com.azentio.aml.domain.enums.KycStatus;
import com.azentio.aml.domain.enums.RiskRating;
import com.azentio.aml.security.PiiMasker;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Full customer record for the detail view.
 *
 * <p>Whether the identifying fields arrive masked or in the clear is decided by the caller's role
 * ({@code piiUnmasked} reports which happened), so the same endpoint serves an analyst and a
 * compliance officer without the client having to know the rules - and without a client-side
 * toggle being able to reveal anything the server did not send.
 */
@Schema(name = "CustomerDetail", description = "Full customer record; PII masking depends on role")
public record CustomerDetail(
        String customerId,
        String firstName,
        String lastName,
        String email,
        String phoneNumber,
        String nationalId,
        Gender gender,
        LocalDate dateOfBirth,
        Integer age,
        String city,
        String state,
        String country,
        String occupation,
        BigDecimal annualIncome,
        EmploymentStatus employmentStatus,
        LocalDate customerSince,
        CustomerSegment segment,
        KycStatus kycStatus,
        RiskRating riskRating,
        boolean politicallyExposed,
        Channel preferredChannel,
        Integer complaintsLastYear,
        List<AccountView> accounts,
        @Schema(description = "False when the caller's role is not entitled to unmasked PII")
                boolean piiUnmasked) {

    public static CustomerDetail from(
            Customer customer, List<AccountView> accounts, boolean unmasked) {
        return new CustomerDetail(
                customer.getCustomerId(),
                PiiMasker.apply(customer.getFirstName(), unmasked, PiiMasker::maskName),
                PiiMasker.apply(customer.getLastName(), unmasked, PiiMasker::maskName),
                PiiMasker.apply(customer.getEmail(), unmasked, PiiMasker::maskEmail),
                PiiMasker.apply(customer.getPhoneNumber(), unmasked, PiiMasker::maskPhone),
                PiiMasker.apply(customer.getNationalId(), unmasked, PiiMasker::maskIdentifier),
                customer.getGender(),
                // The date of birth is itself an identifier; only the year survives masking.
                unmasked ? customer.getDateOfBirth() : null,
                customer.getAge(),
                customer.getCity(),
                customer.getState(),
                customer.getCountry(),
                customer.getOccupation(),
                customer.getAnnualIncome(),
                customer.getEmploymentStatus(),
                customer.getCustomerSince(),
                customer.getCustomerSegment(),
                customer.getKycStatus(),
                customer.getRiskRating(),
                Boolean.TRUE.equals(customer.getPoliticallyExposed()),
                customer.getPreferredChannel(),
                customer.getComplaintsLastYear(),
                accounts,
                unmasked);
    }
}
