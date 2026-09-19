package com.azentio.aml.repository.projection;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One customer's transaction value and count for a single calendar day. */
public interface DailyActivity {

    LocalDate getActivityDate();

    BigDecimal getTotalValue();

    long getTxnCount();
}
