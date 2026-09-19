package com.azentio.aml.service.ingestion;

import com.azentio.aml.domain.enums.AccountStatus;
import com.azentio.aml.domain.enums.AccountTier;
import com.azentio.aml.domain.enums.AccountType;
import com.azentio.aml.domain.enums.CardType;
import com.azentio.aml.domain.enums.RiskRating;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * An account master record as supplied by the upstream feed.
 *
 * <p>{@code customerId} is mandatory and checked against the customer table during ingestion: an
 * account with no owner cannot be attributed to a subject, so its transactions could never be
 * risk-scored against a customer baseline.
 */
public record AccountPayload(
        @NotBlank @Size(max = 32) String accountId,
        @NotBlank @Size(max = 32) String customerId,
        @NotNull AccountType accountType,
        AccountStatus accountStatus,
        @NotBlank @Pattern(regexp = "[A-Za-z]{3}", message = "currency must be a 3-letter ISO code")
                String currency,
        @NotNull LocalDate openDate,
        LocalDate closeDate,
        RiskRating riskRating,
        @Size(max = 20) String branchCode,
        @Size(max = 100) String branchCity,
        BigDecimal currentBalance,
        BigDecimal avgMonthlyBalance6m,
        BigDecimal creditLimit,
        BigDecimal creditUtilizationPct,
        Boolean overdraftEnabled,
        CardType cardType,
        Boolean jointAccount,
        @PositiveOrZero Integer numLinkedDevices,
        Boolean mobileBankingEnrolled,
        LocalDate lastLoginDate,
        @PositiveOrZero Integer avgMonthlyTxnCount,
        AccountTier accountTier) {}
