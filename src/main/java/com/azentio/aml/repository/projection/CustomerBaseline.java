package com.azentio.aml.repository.projection;

import java.math.BigDecimal;

/**
 * A customer's own historical norm, averaged over the days on which they actually transacted.
 *
 * <p>Averaging over active days rather than calendar days matters: a customer who transacts twice a
 * month would otherwise have a near-zero baseline, and every ordinary payment would look like a 3x
 * deviation. {@link #getActiveDays()} lets the engine refuse to score a customer whose history is
 * too thin to be a meaningful baseline.
 */
public interface CustomerBaseline {

    BigDecimal getAvgDailyValue();

    double getAvgDailyCount();

    long getActiveDays();
}
