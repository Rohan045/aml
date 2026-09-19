package com.azentio.aml.service;

import com.azentio.aml.config.SentinelProperties;
import com.azentio.aml.domain.ExchangeRate;
import com.azentio.aml.repository.ExchangeRateRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Normalises every monetary amount into the platform base currency (business rule 9) so rules can
 * compare a USD wire against an INR cash deposit on one scale.
 *
 * <p>Conversion is resolved against the rate <em>effective on the transaction date</em>, not
 * today's rate, so re-running detection over historical data reproduces the original figures. Rates
 * are cached because the table is tiny and read on every ingested row.
 */
@Service
public class CurrencyService {

    private static final Logger log = LoggerFactory.getLogger(CurrencyService.class);
    private static final int BASE_SCALE = 2;
    private static final int RATE_SCALE = 8;

    private final ExchangeRateRepository exchangeRateRepository;
    private final String baseCurrency;

    public CurrencyService(
            ExchangeRateRepository exchangeRateRepository, SentinelProperties properties) {
        this.exchangeRateRepository = exchangeRateRepository;
        this.baseCurrency = properties.getBaseCurrency().toUpperCase(Locale.ROOT);
    }

    public String getBaseCurrency() {
        return baseCurrency;
    }

    /**
     * Converts an amount into the base currency.
     *
     * @param onDate the date the money actually moved
     * @throws IllegalArgumentException when no rate covers the currency on that date - the record is
     *     rejected rather than silently normalised at 1:1, which would corrupt every threshold
     *     comparison downstream
     */
    public Conversion toBaseCurrency(BigDecimal amount, String currency, LocalDate onDate) {
        if (amount == null) {
            throw new IllegalArgumentException("Amount is required for currency conversion");
        }
        String from = normaliseCurrency(currency);
        if (baseCurrency.equals(from)) {
            return new Conversion(
                    amount.setScale(BASE_SCALE, RoundingMode.HALF_UP), BigDecimal.ONE, baseCurrency);
        }
        BigDecimal rate = rateFor(from, onDate == null ? LocalDate.now() : onDate);
        BigDecimal converted = amount.multiply(rate).setScale(BASE_SCALE, RoundingMode.HALF_UP);
        return new Conversion(converted, rate, baseCurrency);
    }

    /**
     * The rate in force on a date. Cached per currency/date pair; the cache is evicted whenever the
     * rate table is amended through {@link #clearRateCache()}.
     */
    @Cacheable(cacheNames = "fxRates", key = "#fromCurrency + ':' + #onDate")
    public BigDecimal rateFor(String fromCurrency, LocalDate onDate) {
        String from = normaliseCurrency(fromCurrency);
        if (baseCurrency.equals(from)) {
            return BigDecimal.ONE;
        }
        List<ExchangeRate> rates =
                exchangeRateRepository.findApplicableRates(
                        from, baseCurrency, onDate, Limit.of(1));
        if (!rates.isEmpty()) {
            return rates.get(0).getRate().setScale(RATE_SCALE, RoundingMode.HALF_UP);
        }
        // Fall back to the inverse leg (BASE -> from) before giving up, so only one direction of
        // each pair has to be maintained in the rate table.
        List<ExchangeRate> inverse =
                exchangeRateRepository.findApplicableRates(
                        baseCurrency, from, onDate, Limit.of(1));
        if (!inverse.isEmpty()) {
            BigDecimal inverseRate = inverse.get(0).getRate();
            if (inverseRate.signum() > 0) {
                return BigDecimal.ONE.divide(inverseRate, RATE_SCALE, RoundingMode.HALF_UP);
            }
        }
        throw new IllegalArgumentException(
                "No exchange rate from "
                        + from
                        + " to "
                        + baseCurrency
                        + " is effective on "
                        + onDate);
    }

    public boolean hasRate(String currency, LocalDate onDate) {
        try {
            rateFor(currency, onDate);
            return true;
        } catch (IllegalArgumentException ex) {
            log.debug("No FX rate for {} on {}", currency, onDate);
            return false;
        }
    }

    @Transactional
    @CacheEvict(cacheNames = "fxRates", allEntries = true)
    public void clearRateCache() {
        log.info("FX rate cache cleared");
    }

    private static String normaliseCurrency(String currency) {
        if (currency == null || currency.isBlank()) {
            throw new IllegalArgumentException("Currency code is required");
        }
        return currency.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * Result of a conversion.
     *
     * @param baseAmount the amount expressed in {@code baseCurrency}
     * @param rate the rate applied, retained on the transaction for auditability
     */
    public record Conversion(BigDecimal baseAmount, BigDecimal rate, String baseCurrency) {}
}
