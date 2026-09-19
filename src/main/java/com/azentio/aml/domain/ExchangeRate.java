package com.azentio.aml.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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

/**
 * Configurable exchange rate used to normalise every transaction amount into the platform base
 * currency (USD) so thresholds are comparable across currencies.
 *
 * <p>Rates are effective-dated: a transaction is converted using the row whose validity period
 * contains its value date, which keeps historical alerts reproducible.
 */
@Entity
@Table(
        name = "exchange_rates",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_rate_currency_effective",
                        columnNames = {"from_currency", "to_currency", "effective_from"}),
        indexes = @Index(name = "idx_rate_lookup", columnList = "from_currency,to_currency,effective_from"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(onlyExplicitlyIncluded = true)
public class ExchangeRate extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @NotBlank
    @Size(min = 3, max = 3)
    @Column(name = "from_currency", length = 3, nullable = false)
    @ToString.Include
    private String fromCurrency;

    @NotBlank
    @Size(min = 3, max = 3)
    @Column(name = "to_currency", length = 3, nullable = false)
    @ToString.Include
    @Builder.Default
    private String toCurrency = "USD";

    /** Units of {@link #toCurrency} per single unit of {@link #fromCurrency}. */
    @NotNull
    @DecimalMin(value = "0.0", inclusive = false)
    @Column(name = "rate", precision = 19, scale = 8, nullable = false)
    @ToString.Include
    private BigDecimal rate;

    @NotNull
    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    /** Null means the rate is still current. */
    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Size(max = 120)
    @Column(name = "source", length = 120)
    private String source;

    public boolean coversDate(LocalDate date) {
        return !date.isBefore(effectiveFrom) && (effectiveTo == null || date.isBefore(effectiveTo));
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ExchangeRate that)) {
            return false;
        }
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
