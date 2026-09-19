package com.azentio.aml.repository;

import com.azentio.aml.domain.ExchangeRate;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Effective-dated FX rates used to normalise every amount into the base currency.
 *
 * <p>Lookups are by transaction date, not "now", so re-running detection over historical data
 * reproduces the original figures exactly.
 */
public interface ExchangeRateRepository extends JpaRepository<ExchangeRate, Long> {

    /**
     * The rate in force on a given date, most recent effective date first.
     *
     * <p>Returns a list with a {@link Limit} rather than a single result because overlapping rows
     * are possible when a correction is loaded; taking the newest effective row is the intended
     * resolution.
     */
    @Query(
            """
            select r
              from ExchangeRate r
             where r.fromCurrency = :fromCurrency
               and r.toCurrency   = :toCurrency
               and r.effectiveFrom <= :on
               and (r.effectiveTo is null or r.effectiveTo >= :on)
             order by r.effectiveFrom desc
            """)
    List<ExchangeRate> findApplicableRates(
            @Param("fromCurrency") String fromCurrency,
            @Param("toCurrency") String toCurrency,
            @Param("on") LocalDate on,
            Limit limit);

    /** Every rate in force on a date, for warming an in-memory conversion table. */
    @Query(
            """
            select r
              from ExchangeRate r
             where r.toCurrency = :toCurrency
               and r.effectiveFrom <= :on
               and (r.effectiveTo is null or r.effectiveTo >= :on)
             order by r.fromCurrency asc, r.effectiveFrom desc
            """)
    List<ExchangeRate> findAllApplicable(
            @Param("toCurrency") String toCurrency, @Param("on") LocalDate on);
}
