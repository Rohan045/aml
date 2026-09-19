package com.azentio.aml.common;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Presentation helpers for the human-readable alert explanations.
 *
 * <p>Alerts are read by compliance analysts and, eventually, by a regulator, so figures are rendered
 * with thousands separators and two decimals, and timestamps in UTC with an explicit marker. The
 * formatters are created per call because {@link DecimalFormat} is not thread-safe and the detection
 * engine is concurrent.
 */
public final class Format {

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

    private Format() {}

    public static String money(BigDecimal amount, String currency) {
        if (amount == null) {
            return "-";
        }
        return currency + " " + new DecimalFormat("#,##0.00").format(amount);
    }

    public static String number(BigDecimal value) {
        if (value == null) {
            return "-";
        }
        return new DecimalFormat("#,##0.00").format(value);
    }

    public static String multiplier(BigDecimal value) {
        if (value == null) {
            return "-";
        }
        return new DecimalFormat("0.0#").format(value) + "x";
    }

    public static String percent(BigDecimal ratio) {
        if (ratio == null) {
            return "-";
        }
        return new DecimalFormat("0.#")
                        .format(ratio.multiply(BigDecimal.valueOf(100))
                                .setScale(1, RoundingMode.HALF_UP))
                + "%";
    }

    public static String timestamp(Instant instant) {
        return instant == null ? "-" : TIMESTAMP.format(instant);
    }

    public static String date(Instant instant) {
        return instant == null ? "-" : DATE.format(instant);
    }
}
