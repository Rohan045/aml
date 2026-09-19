package com.azentio.aml.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Small JSON helper for the evidence and audit snapshots stored as text columns.
 *
 * <p>Serialisation never throws: a detail payload that cannot be rendered is degraded to an empty
 * object rather than failing the alert it was describing.
 */
public final class Json {

    private static final Logger log = LoggerFactory.getLogger(Json.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {}

    /** Serialises alternating key/value pairs, skipping null values. */
    public static String of(Object... keyValuePairs) {
        if (keyValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException("JSON details require key/value pairs");
        }
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            Object value = keyValuePairs[i + 1];
            if (value != null) {
                values.put(String.valueOf(keyValuePairs[i]), value);
            }
        }
        return write(values);
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception ex) {
            log.warn("Unable to serialise detail payload: {}", ex.getMessage());
            return "{}";
        }
    }
}
