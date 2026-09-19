package com.azentio.aml.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Externalised platform settings. Everything here is tunable per environment; nothing that is
 * environment-specific or secret is compiled into the application.
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "sentinel")
public class SentinelProperties {

    /** Currency every amount is normalised into before any rule threshold is applied. */
    @NotBlank
    private String baseCurrency = "USD";

    private final Detection detection = new Detection();
    private final Ingestion ingestion = new Ingestion();
    private final Streaming streaming = new Streaming();

    @Getter
    @Setter
    public static class Detection {

        /** Runs the periodic sweep over recently ingested transactions. */
        private boolean scheduledSweepEnabled = true;

        /** How far back each scheduled sweep looks, in hours. */
        @Positive
        private int sweepLookbackHours = 48;

        /** Transactions pulled from the screening queue per sweep page. */
        @Positive
        private int screeningPageSize = 1000;

        /** Upper bound on the composite alert risk score. */
        @Positive
        private int maxRiskScore = 100;

        /** Uplift added when the customer is flagged politically exposed. */
        private int pepUplift = 10;

        /** Detection worker threads used for bulk evaluation. */
        @Positive
        private int workerThreads = 4;
    }

    @Getter
    @Setter
    public static class Ingestion {

        /** Rows flushed to the database per JDBC batch. */
        @Positive
        private int batchSize = 500;

        /** Hard cap on rows accepted in one upload, to bound memory. */
        @Positive
        private int maxRecordsPerBatch = 200_000;

        /** Detection runs automatically on newly ingested transactions. */
        private boolean detectOnIngest = true;
    }

    @Getter
    @Setter
    public static class Streaming {

        /** Enables the Kafka consumer; off by default so the app boots without a broker. */
        private boolean enabled = false;

        private String topic = "sentinel.transactions";

        private String groupId = "sentinel-detection";
    }
}
