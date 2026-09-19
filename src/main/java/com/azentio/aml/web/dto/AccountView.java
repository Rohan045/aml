package com.azentio.aml.web.dto;

import com.azentio.aml.domain.Account;
import com.azentio.aml.domain.enums.AccountStatus;
import com.azentio.aml.domain.enums.AccountTier;
import com.azentio.aml.domain.enums.AccountType;
import com.azentio.aml.domain.enums.RiskRating;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Account metadata. Carries no PII, so it is returned identically to every entitled role. */
@Schema(name = "AccountView", description = "Account master record")
public record AccountView(
        @Schema(example = "ACC_000001") String accountId,
        @Schema(example = "CUST_00001") String customerId,
        AccountType accountType,
        AccountStatus accountStatus,
        @Schema(example = "INR") String currency,
        LocalDate openDate,
        LocalDate closeDate,
        RiskRating riskRating,
        String branchCode,
        String branchCity,
        BigDecimal currentBalance,
        BigDecimal avgMonthlyBalance6m,
        AccountTier accountTier,
        Integer avgMonthlyTxnCount) {

    public static AccountView from(Account account) {
        return new AccountView(
                account.getAccountId(),
                // Reads the foreign key off the lazy proxy; does not trigger a customer select.
                account.getCustomer() == null ? null : account.getCustomer().getCustomerId(),
                account.getAccountType(),
                account.getAccountStatus(),
                account.getCurrency(),
                account.getOpenDate(),
                account.getCloseDate(),
                account.getRiskRating(),
                account.getBranchCode(),
                account.getBranchCity(),
                account.getCurrentBalance(),
                account.getAvgMonthlyBalance6m(),
                account.getAccountTier(),
                account.getAvgMonthlyTxnCount());
    }
}
