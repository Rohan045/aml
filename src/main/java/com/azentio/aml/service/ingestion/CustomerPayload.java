package com.azentio.aml.service.ingestion;

import com.azentio.aml.domain.enums.Channel;
import com.azentio.aml.domain.enums.CustomerSegment;
import com.azentio.aml.domain.enums.EducationLevel;
import com.azentio.aml.domain.enums.EmploymentStatus;
import com.azentio.aml.domain.enums.Gender;
import com.azentio.aml.domain.enums.KycStatus;
import com.azentio.aml.domain.enums.MaritalStatus;
import com.azentio.aml.domain.enums.RiskRating;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A customer KYC record as supplied by the upstream feed (CSV row or REST body).
 *
 * <p>Only the identifier and given name are mandatory. Everything else is optional because a KYC
 * export with a missing occupation is still a perfectly monitorable customer, and rejecting it
 * would leave that customer's transactions unscreenable - the opposite of what the regulator wants.
 */
public record CustomerPayload(
        @NotBlank @Size(max = 32) String customerId,
        @NotBlank @Size(max = 100) String firstName,
        @Size(max = 100) String lastName,
        Gender gender,
        LocalDate dateOfBirth,
        @PositiveOrZero Integer age,
        @Email @Size(max = 160) String email,
        @Size(max = 32) String phoneNumber,
        @Size(max = 64) String nationalId,
        @Size(max = 100) String city,
        @Size(max = 100) String state,
        @Size(max = 2) String country,
        @Size(max = 20) String postalCode,
        @Size(max = 120) String occupation,
        BigDecimal annualIncome,
        MaritalStatus maritalStatus,
        EducationLevel educationLevel,
        EmploymentStatus employmentStatus,
        LocalDate customerSince,
        CustomerSegment customerSegment,
        KycStatus kycStatus,
        RiskRating riskRating,
        Boolean politicallyExposed,
        Channel preferredChannel,
        Boolean emailVerified,
        Boolean phoneVerified,
        @PositiveOrZero Integer complaintsLastYear) {}
