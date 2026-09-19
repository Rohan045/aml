package com.azentio.aml.domain;

import com.azentio.aml.domain.enums.Channel;
import com.azentio.aml.domain.enums.ScreeningStatus;
import com.azentio.aml.domain.enums.TransactionDirection;
import com.azentio.aml.domain.enums.TransactionStatus;
import com.azentio.aml.domain.enums.TransactionType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
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
 * A single posted movement of money on an account, from either bulk load or the streaming feed.
 *
 * <p>{@code externalTxnId} is unique so replays of the same source record are idempotent and cannot
 * produce duplicate alerts. {@code baseAmount} holds the amount normalised to the platform base
 * currency (USD) so rules can compare values across currencies. The {@code customer} reference is
 * denormalised from the account to keep customer-level rule queries single-table.
 */
@Entity
@Table(
        name = "transactions",
        uniqueConstraints =
                @UniqueConstraint(name = "uk_transaction_external_id", columnNames = "external_txn_id"),
        indexes = {
            @Index(name = "idx_txn_account_time", columnList = "account_id,transaction_timestamp"),
            @Index(name = "idx_txn_customer_time", columnList = "customer_id,transaction_timestamp"),
            @Index(name = "idx_txn_timestamp", columnList = "transaction_timestamp"),
            @Index(name = "idx_txn_screening_status", columnList = "screening_status"),
            @Index(name = "idx_txn_base_amount", columnList = "base_amount"),
            @Index(name = "idx_txn_counterparty_country", columnList = "counterparty_country"),
            @Index(name = "idx_txn_batch", columnList = "ingestion_batch_id")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(onlyExplicitlyIncluded = true)
public class Transaction extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** Business identifier from the source system; enforces ingestion idempotency. */
    @NotBlank
    @Size(max = 64)
    @Column(name = "external_txn_id", length = 64, nullable = false, updatable = false)
    @ToString.Include
    private String externalTxnId;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "account_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_transaction_account"))
    private Account account;

    /** Denormalised owner of {@link #account}; kept in sync by the ingestion service. */
    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "customer_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_transaction_customer"))
    private Customer customer;

    @NotNull
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "direction", length = 10, nullable = false)
    private TransactionDirection direction;

    @NotNull
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "transaction_type", length = 32, nullable = false)
    private TransactionType transactionType;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "channel", length = 32)
    private Channel channel;

    @NotNull
    @DecimalMin(value = "0.0", inclusive = false)
    @Column(name = "amount", precision = 19, scale = 2, nullable = false)
    @ToString.Include
    private BigDecimal amount;

    @NotBlank
    @Size(min = 3, max = 3)
    @Column(name = "currency", length = 3, nullable = false)
    private String currency;

    /** Rate applied to convert {@link #amount} into {@link #baseAmount}. */
    @Column(name = "exchange_rate", precision = 19, scale = 8)
    private BigDecimal exchangeRate;

    /** {@link #amount} normalised to {@link #baseCurrency}; all rule thresholds compare on this. */
    @NotNull
    @Column(name = "base_amount", precision = 19, scale = 2, nullable = false)
    private BigDecimal baseAmount;

    @NotBlank
    @Size(min = 3, max = 3)
    @Column(name = "base_currency", length = 3, nullable = false)
    @Builder.Default
    private String baseCurrency = "USD";

    @NotNull
    @Column(name = "transaction_timestamp", nullable = false)
    private Instant transactionTimestamp;

    @Column(name = "value_date")
    private LocalDate valueDate;

    @NotNull
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", length = 20, nullable = false)
    @Builder.Default
    private TransactionStatus status = TransactionStatus.POSTED;

    @Size(max = 500)
    @Column(name = "narration", length = 500)
    private String narration;

    @Column(name = "balance_after", precision = 19, scale = 2)
    private BigDecimal balanceAfter;

    @Size(max = 200)
    @Column(name = "counterparty_name", length = 200)
    private String counterpartyName;

    @Size(max = 64)
    @Column(name = "counterparty_account", length = 64)
    private String counterpartyAccount;

    @Size(max = 200)
    @Column(name = "counterparty_bank", length = 200)
    private String counterpartyBank;

    /** SWIFT/BIC or IFSC of the counterparty institution. */
    @Size(max = 32)
    @Column(name = "counterparty_bank_code", length = 32)
    private String counterpartyBankCode;

    /** ISO 3166-1 alpha-2; screened against the high-risk jurisdiction watchlist. */
    @Size(max = 2)
    @Column(name = "counterparty_country", length = 2)
    private String counterpartyCountry;

    @Size(max = 2)
    @Column(name = "origin_country", length = 2)
    private String originCountry;

    @Size(max = 2)
    @Column(name = "destination_country", length = 2)
    private String destinationCountry;

    @NotNull
    @Column(name = "is_cross_border", nullable = false)
    @Builder.Default
    private Boolean crossBorder = Boolean.FALSE;

    @Size(max = 8)
    @Column(name = "merchant_category_code", length = 8)
    private String merchantCategoryCode;

    @Size(max = 20)
    @Column(name = "branch_code", length = 20)
    private String branchCode;

    @Size(max = 100)
    @Column(name = "device_id", length = 100)
    private String deviceId;

    @Size(max = 64)
    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    /** Set once the detection engine has evaluated this transaction. */
    @NotNull
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "screening_status", length = 20, nullable = false)
    @Builder.Default
    private ScreeningStatus screeningStatus = ScreeningStatus.PENDING;

    @Column(name = "screened_at")
    private Instant screenedAt;

    @Column(name = "ingestion_batch_id")
    private Long ingestionBatchId;

    public boolean isInflow() {
        return direction == TransactionDirection.CREDIT;
    }

    public boolean isOutflow() {
        return direction == TransactionDirection.DEBIT;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Transaction that)) {
            return false;
        }
        return externalTxnId != null && externalTxnId.equals(that.externalTxnId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(externalTxnId);
    }
}
