package com.azentio.aml.service.ingestion;

import com.azentio.aml.common.EnumSupport;
import com.azentio.aml.common.exception.IngestionException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

/**
 * Header-driven CSV reading for the bulk ingestion feeds.
 *
 * <p>Columns are addressed by name rather than position, so the bank can add, reorder or omit
 * optional columns in an export without breaking the loader - a positional reader would silently
 * mis-assign every field after the change, which is far worse than rejecting the file.
 *
 * <p>Accessors are deliberately lenient: a blank cell yields {@code null} rather than an exception,
 * because "this optional field was not supplied" is normal and must not fail the record. Values
 * that are present but genuinely unparseable do throw, so the record is rejected with a precise
 * reason instead of being silently loaded with a wrong value.
 */
public final class CsvSupport {

    /** Date formats seen across the customer, account and transaction exports. */
    private static final List<DateTimeFormatter> DATE_FORMATS =
            List.of(
                    DateTimeFormatter.ISO_LOCAL_DATE,
                    DateTimeFormatter.ofPattern("dd-MM-yyyy", Locale.ROOT),
                    DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ROOT),
                    DateTimeFormatter.ofPattern("yyyy/MM/dd", Locale.ROOT));

    private static final List<DateTimeFormatter> DATE_TIME_FORMATS =
            List.of(
                    DateTimeFormatter.ISO_LOCAL_DATE_TIME,
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT),
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT),
                    DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss", Locale.ROOT),
                    DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss", Locale.ROOT));

    private CsvSupport() {}

    /**
     * Reads the whole file into memory.
     *
     * <p>Bounded by {@code sentinel.ingestion.max-records-per-batch} at the service layer, and the
     * rules need the complete set anyway to evaluate rolling windows, so streaming the parse would
     * add complexity without reducing peak memory.
     *
     * @throws IngestionException when the stream is not readable CSV or carries no header
     */
    public static List<Row> read(InputStream inputStream) {
        List<Row> rows = new ArrayList<>();
        CSVFormat format =
                CSVFormat.DEFAULT
                        .builder()
                        .setHeader()
                        .setSkipHeaderRecord(true)
                        .setIgnoreHeaderCase(true)
                        .setTrim(true)
                        .setIgnoreEmptyLines(true)
                        .setAllowMissingColumnNames(true)
                        .build();
        try (BufferedReader reader =
                        new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
                CSVParser parser = CSVParser.parse(reader, format)) {
            if (parser.getHeaderMap() == null || parser.getHeaderMap().isEmpty()) {
                throw new IngestionException("The CSV file has no header row");
            }
            for (CSVRecord record : parser) {
                rows.add(new Row(record.getRecordNumber(), record.toMap()));
            }
        } catch (IOException | IllegalArgumentException ex) {
            throw new IngestionException("The CSV file could not be parsed: " + ex.getMessage());
        }
        if (rows.isEmpty()) {
            throw new IngestionException("The CSV file contains a header but no data rows");
        }
        return rows;
    }

    /**
     * One data row, addressed by (case-insensitive) column name.
     *
     * @param lineNumber source line, echoed back in rejection reports so an operator can find the
     *     offending row in the original file
     */
    public record Row(long lineNumber, Map<String, String> values) {

        public String get(String column) {
            String value = values.get(column);
            if (value == null) {
                // Commons-CSV lower-cases nothing; tolerate exports that differ only in case.
                for (Map.Entry<String, String> entry : values.entrySet()) {
                    if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(column)) {
                        value = entry.getValue();
                        break;
                    }
                }
            }
            return value == null || value.isBlank() ? null : value.trim();
        }

        /** First of {@code columns} that carries a value; lets one loader accept feed variants. */
        public String any(String... columns) {
            for (String column : columns) {
                String value = get(column);
                if (value != null) {
                    return value;
                }
            }
            return null;
        }

        public String upper(String column) {
            String value = get(column);
            return value == null ? null : value.toUpperCase(Locale.ROOT);
        }

        public BigDecimal decimal(String column) {
            String value = get(column);
            if (value == null) {
                return null;
            }
            try {
                // Strips thousands separators and currency symbols the exports sometimes carry.
                return new BigDecimal(value.replaceAll("[,\\s\u20B9$€£]", ""));
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException(
                        "Column '" + column + "' is not a number: " + value);
            }
        }

        public Integer integer(String column) {
            BigDecimal value = decimal(column);
            return value == null ? null : value.intValue();
        }

        public Long number(String column) {
            BigDecimal value = decimal(column);
            return value == null ? null : value.longValue();
        }

        public Boolean flag(String column, Boolean fallback) {
            return EnumSupport.parseFlag(get(column), fallback);
        }

        public <E extends Enum<E>> E enumeration(Class<E> type, String column, E fallback) {
            return EnumSupport.parse(type, get(column), fallback);
        }

        public LocalDate date(String column) {
            String value = get(column);
            if (value == null) {
                return null;
            }
            for (DateTimeFormatter formatter : DATE_FORMATS) {
                try {
                    return LocalDate.parse(value, formatter);
                } catch (RuntimeException ignored) {
                    // Try the next known layout.
                }
            }
            // An export may carry a full timestamp in a date column; take its date part.
            Instant instant = tryInstant(value);
            if (instant != null) {
                return instant.atZone(ZoneOffset.UTC).toLocalDate();
            }
            throw new IllegalArgumentException("Column '" + column + "' is not a date: " + value);
        }

        public Instant instant(String column) {
            String value = get(column);
            if (value == null) {
                return null;
            }
            Instant instant = tryInstant(value);
            if (instant != null) {
                return instant;
            }
            // A date-only value means midnight UTC on that day.
            for (DateTimeFormatter formatter : DATE_FORMATS) {
                try {
                    return LocalDate.parse(value, formatter).atStartOfDay(ZoneOffset.UTC).toInstant();
                } catch (RuntimeException ignored) {
                    // Try the next known layout.
                }
            }
            throw new IllegalArgumentException(
                    "Column '" + column + "' is not a timestamp: " + value);
        }

        /** Timestamps without an offset are read as UTC, which is how the platform stores them. */
        private static Instant tryInstant(String value) {
            try {
                return Instant.parse(value);
            } catch (RuntimeException ignored) {
                // Not an ISO instant; fall through to the local layouts.
            }
            for (DateTimeFormatter formatter : DATE_TIME_FORMATS) {
                try {
                    return LocalDateTime.parse(value, formatter).toInstant(ZoneOffset.UTC);
                } catch (RuntimeException ignored) {
                    // Try the next known layout.
                }
            }
            return null;
        }
    }
}
