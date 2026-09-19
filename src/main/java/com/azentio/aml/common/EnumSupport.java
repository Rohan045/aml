package com.azentio.aml.common;

import java.util.Locale;

/**
 * Lenient parsing helpers used by the ingestion layer, where source feeds supply values such as
 * {@code "Phone Banking"}, {@code "self-employed"} or {@code "Y"} that must be mapped onto strict
 * domain enums without failing the whole batch.
 */
public final class EnumSupport {

    private EnumSupport() {
    }

    /**
     * Normalises a raw feed value ({@code "Phone Banking"} -> {@code "PHONE_BANKING"}) and resolves
     * it against the given enum type.
     *
     * @return the matching constant, or {@code fallback} when the value is blank or unknown
     */
    public static <E extends Enum<E>> E parse(Class<E> type, String raw, E fallback) {
        String normalized = normalize(raw);
        if (normalized == null) {
            return fallback;
        }
        for (E constant : type.getEnumConstants()) {
            if (constant.name().equals(normalized)) {
                return constant;
            }
        }
        return fallback;
    }

    public static <E extends Enum<E>> E parse(Class<E> type, String raw) {
        return parse(type, raw, null);
    }

    /** Uppercases and collapses every non-alphanumeric run into a single underscore. */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
    }

    /** Maps {@code Y/YES/TRUE/1} and {@code N/NO/FALSE/0} flags used across the CSV feeds. */
    public static Boolean parseFlag(String raw, Boolean fallback) {
        String normalized = normalize(raw);
        if (normalized == null) {
            return fallback;
        }
        return switch (normalized) {
            case "Y", "YES", "TRUE", "T", "1" -> Boolean.TRUE;
            case "N", "NO", "FALSE", "F", "0" -> Boolean.FALSE;
            default -> fallback;
        };
    }
}
