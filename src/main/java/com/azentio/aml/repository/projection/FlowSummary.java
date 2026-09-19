package com.azentio.aml.repository.projection;

import java.math.BigDecimal;

/**
 * Total money in and out of an account over a window, in base currency.
 *
 * <p>Drives the rapid-movement rule: if {@code outflow / inflow} meets the configured ratio inside
 * the configured window, funds are passing through rather than being held.
 */
public interface FlowSummary {

    BigDecimal getInflow();

    BigDecimal getOutflow();
}
