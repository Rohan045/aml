package com.azentio.aml.detection;

import com.azentio.aml.domain.RuleConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Typed access to the free-form {@code parameters} JSON on a rule configuration.
 *
 * <p>The columns on {@code rule_configs} cover the thresholds every rule needs; this carries the
 * rule-specific extras (which transaction types a rule applies to, the rounding multiple, minimum
 * baseline length) so a new rule does not require a schema change.
 *
 * <p>Malformed or missing JSON degrades to the caller's default rather than failing the sweep: a
 * mistyped optional parameter must not take the whole detection engine offline.
 */
public final class RuleParameters {

    private static final Logger log = LoggerFactory.getLogger(RuleParameters.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final RuleParameters EMPTY = new RuleParameters(Map.of());

    private final Map<String, Object> values;

    private RuleParameters(Map<String, Object> values) {
        this.values = values;
    }

    public static RuleParameters of(RuleConfig config) {
        if (config == null || config.getParameters() == null || config.getParameters().isBlank()) {
            return EMPTY;
        }
        try {
            Map<String, Object> parsed =
                    MAPPER.readValue(config.getParameters(), new TypeReference<>() {});
            return new RuleParameters(parsed == null ? Map.of() : parsed);
        } catch (Exception ex) {
            log.warn(
                    "Rule {} has unreadable parameters JSON; falling back to defaults: {}",
                    config.getRuleCode(),
                    ex.getMessage());
            return EMPTY;
        }
    }

    public BigDecimal decimal(String key, BigDecimal fallback) {
        Object value = values.get(key);
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return new BigDecimal(text.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    public int integer(String key, int fallback) {
        Object value = values.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    public String text(String key, String fallback) {
        Object value = values.get(key);
        return value instanceof String text && !text.isBlank() ? text : fallback;
    }

    public boolean flag(String key, boolean fallback) {
        Object value = values.get(key);
        return value instanceof Boolean bool ? bool : fallback;
    }

    /** A JSON array of strings, e.g. the transaction types a rule applies to. */
    @SuppressWarnings("unchecked")
    public List<String> strings(String key) {
        Object value = values.get(key);
        if (value instanceof List<?> list) {
            return (List<String>) (List<?>) list.stream().map(String::valueOf).toList();
        }
        return Collections.emptyList();
    }
}
