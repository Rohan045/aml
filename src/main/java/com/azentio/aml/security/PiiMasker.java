package com.azentio.aml.security;

/**
 * Masks customer PII for list views (business rule 8).
 *
 * <p>Masking is applied when the response is assembled, not in the persistence layer, so the
 * underlying record stays intact for investigation while unauthorised roles never receive the raw
 * value over the wire. Each masker keeps just enough signal for an analyst to recognise a record
 * they already have legitimate access to, without disclosing the identifier itself.
 */
public final class PiiMasker {

    private static final String MASK = "****";

    private PiiMasker() {}

    /** {@code Krishna Sharma} -> {@code K***** S*****}. */
    public static String maskName(String name) {
        if (isBlank(name)) {
            return name;
        }
        StringBuilder masked = new StringBuilder();
        for (String part : name.trim().split("\\s+")) {
            if (masked.length() > 0) {
                masked.append(' ');
            }
            masked.append(part.charAt(0)).append("*".repeat(Math.max(1, part.length() - 1)));
        }
        return masked.toString();
    }

    /** Keeps only the last four characters of an identity document number. */
    public static String maskIdentifier(String value) {
        if (isBlank(value)) {
            return value;
        }
        String trimmed = value.trim();
        if (trimmed.length() <= 4) {
            return MASK;
        }
        return MASK + trimmed.substring(trimmed.length() - 4);
    }

    /** {@code krishna.sharma@gmail.com} -> {@code k****a@gmail.com}. */
    public static String maskEmail(String email) {
        if (isBlank(email)) {
            return email;
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            return MASK;
        }
        String local = email.substring(0, at);
        String domain = email.substring(at);
        if (local.length() <= 2) {
            return local.charAt(0) + MASK + domain;
        }
        return local.charAt(0) + MASK + local.charAt(local.length() - 1) + domain;
    }

    /** Keeps the last four digits so an analyst can match a number they already hold. */
    public static String maskPhone(String phone) {
        if (isBlank(phone)) {
            return phone;
        }
        String digits = phone.replaceAll("\\D", "");
        if (digits.length() <= 4) {
            return MASK;
        }
        return MASK + digits.substring(digits.length() - 4);
    }

    /** Applies {@code masker} only when the caller is not entitled to the raw value. */
    public static String apply(String value, boolean unmasked, Masker masker) {
        return unmasked ? value : masker.mask(value);
    }

    @FunctionalInterface
    public interface Masker {
        String mask(String value);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
