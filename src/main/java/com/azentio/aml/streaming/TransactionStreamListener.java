package com.azentio.aml.streaming;

import com.azentio.aml.domain.enums.IngestionSource;
import com.azentio.aml.service.ingestion.IngestionResult;
import com.azentio.aml.service.ingestion.TransactionIngestionService;
import com.azentio.aml.service.ingestion.TransactionPayload;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * Continuous transaction ingestion from Kafka - the "near real-time" half of the mandate, as
 * opposed to the nightly spreadsheet the bank is replacing.
 *
 * <p>Disabled unless {@code sentinel.streaming.enabled=true} so the application boots and the whole
 * REST/CSV workflow demos without a broker running.
 *
 * <p>Three deliberate decisions:
 *
 * <ul>
 *   <li><b>Payload consumed as a JSON string.</b> Deserialisation happens here rather than in the
 *       consumer factory, so a malformed message produces a logged rejection instead of a
 *       container-level deserialisation error that stalls the partition.
 *   <li><b>At-least-once delivery is assumed.</b> The ingestion service keys on
 *       {@code externalTxnId} and treats a repeat as a duplicate, so a redelivery after a broker
 *       hiccup cannot raise a second alert for the same transaction.
 *   <li><b>Poison messages are not retried forever.</b> A message that cannot be parsed will never
 *       parse, so it is logged and the offset advances.
 * </ul>
 *
 * <p>Concurrency is safe by construction: partitions may be consumed in parallel because all
 * coordination between detection threads happens through the database's unique constraints.
 */
@Component
@ConditionalOnProperty(prefix = "sentinel.streaming", name = "enabled", havingValue = "true")
public class TransactionStreamListener {

    private static final Logger log = LoggerFactory.getLogger(TransactionStreamListener.class);

    private final TransactionIngestionService ingestionService;
    private final ObjectMapper objectMapper;

    public TransactionStreamListener(
            TransactionIngestionService ingestionService, ObjectMapper objectMapper) {
        this.ingestionService = ingestionService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = "${sentinel.streaming.topic:sentinel.transactions}",
            groupId = "${sentinel.streaming.group-id:sentinel-detection}",
            concurrency = "${sentinel.streaming.concurrency:3}")
    public void onTransaction(
            @Payload String message,
            @Header(name = KafkaHeaders.RECEIVED_KEY, required = false) String key,
            @Header(name = KafkaHeaders.RECEIVED_PARTITION, required = false) Integer partition,
            @Header(name = KafkaHeaders.OFFSET, required = false) Long offset) {
        TransactionPayload payload;
        try {
            payload = objectMapper.readValue(message, TransactionPayload.class);
        } catch (Exception ex) {
            // Unparseable now means unparseable on every retry; log so the partition advances.
            log.error(
                    "Rejecting unparseable transaction message at partition {} offset {} (key {}):"
                            + " {}",
                    partition,
                    offset,
                    key,
                    ex.getMessage());
            return;
        }

        long startedAt = System.currentTimeMillis();
        IngestionResult result = ingestionService.ingestOne(payload, IngestionSource.KAFKA_STREAM);
        if (result.hasErrors()) {
            log.warn(
                    "Streaming transaction {} rejected: {}",
                    payload.externalTxnId(),
                    result.errors().get(0).errorMessage());
            return;
        }
        if (result.duplicates() > 0) {
            log.debug("Streaming transaction {} already ingested; skipped", payload.externalTxnId());
            return;
        }
        log.info(
                "Streaming transaction {} screened in {} ms; {} alert(s) raised, {} aggregated",
                payload.externalTxnId(),
                System.currentTimeMillis() - startedAt,
                result.alertsCreated(),
                result.alertsAggregated());
    }
}
