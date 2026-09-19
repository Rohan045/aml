package com.azentio.aml.domain;

import com.azentio.aml.domain.enums.AccountStatus;
import com.azentio.aml.domain.enums.AccountTier;
import com.azentio.aml.domain.enums.AccountType;
import com.azentio.aml.domain.enums.CardType;
import com.azentio.aml.domain.enums.RiskRating;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Account master record, mirroring the {@code accounts.csv} feed. Every transaction belongs to
 * exactly one account, and every account to exactly one customer.
 */
@Entity
@Table(
        name = "accounts",
        indexes = {
            @Index(name = "idx_account_customer", columnList = "customer_id"),
            @Index(name = "idx_account_status", columnList = "account_status"),
            @Index(name = "idx_account_type", columnList = "account_type"),
            @Index(name = "idx_account_risk_rating", columnList = "risk_rating")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(onlyExplicitlyIncluded = true)
public class Account extends BaseEntity {

    @Id
    @NotBlank
    @Size(max = 32)
    @Column(name = "account_id", length = 32, nullable = false, updatable = false)
    @ToString.Include
    private String accountId;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "customer_id",
            nullable = false,
            foreignKey = @jakarta.persistence.ForeignKey(name = "fk_account_customer"))
    private Customer customer;

    @NotNull
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "account_type", length = 32, nullable = false)
    private AccountType accountType;

    @NotNull
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "account_status", length = 20, nullable = false)
    @Builder.Default
    private AccountStatus accountStatus = AccountStatus.ACTIVE;

    /** ISO 4217 code of the account's operating currency. */
    @NotBlank
    @Size(min = 3, max = 3)
    @Column(name = "currency", length = 3, nullable = false)
    @Builder.Default
    private String currency = "INR";

    @NotNull
    @Column(name = "open_date", nullable = false)
    private LocalDate openDate;

    @Column(name = "close_date")
    private LocalDate closeDate;

    /**
     * Account-level AML risk rating. Defaults to the owning customer's rating but may be overridden
     * by compliance.
     */
    @NotNull
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "risk_rating", length = 20, nullable = false)
    @Builder.Default
    private RiskRating riskRating = RiskRating.LOW;

    @Size(max = 20)
    @Column(name = "branch_code", length = 20)
    private String branchCode;

    @Size(max = 100)
    @Column(name = "branch_city", length = 100)
    private String branchCity;

    @Column(name = "current_balance", precision = 19, scale = 2)
    private BigDecimal currentBalance;

    @Column(name = "avg_monthly_balance_6m", precision = 19, scale = 2)
    private BigDecimal avgMonthlyBalance6m;

    @Column(name = "credit_limit", precision = 19, scale = 2)
    private BigDecimal creditLimit;

    @Column(name = "credit_utilization_pct", precision = 7, scale = 2)
    private BigDecimal creditUtilizationPct;

    @Column(name = "overdraft_enabled")
    private Boolean overdraftEnabled;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "card_type", length = 20)
    private CardType cardType;

    @Column(name = "is_joint_account")
    private Boolean jointAccount;

    @PositiveOrZero
    @Column(name = "num_linked_devices")
    private Integer numLinkedDevices;

    @Column(name = "mobile_banking_enrolled")
    private Boolean mobileBankingEnrolled;

    @Column(name = "last_login_date")
    private LocalDate lastLoginDate;

    @PositiveOrZero
    @Column(name = "avg_monthly_txn_count")
    private Integer avgMonthlyTxnCount;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "account_tier", length = 20)
    private AccountTier accountTier;

    public boolean isOpen() {
        return closeDate == null && accountStatus != AccountStatus.CLOSED;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Account that)) {
            return false;
        }
        return accountId != null && accountId.equals(that.accountId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(accountId);
    }
}
