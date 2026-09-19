package com.azentio.aml.web.dto;

import com.azentio.aml.domain.AlertEvidence;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * One transaction cited as evidence for an alert.
 *
 * <p>{@code evidenceRole} names the part the transaction played in the pattern (for example
 * {@code STRUCTURING_LEG} or {@code OUTFLOW_LEG}), which is what turns a list of transaction ids
 * into an argument an analyst can follow and a regulator can audit.
 */
@Schema(name = "EvidenceView", description = "A transaction supporting an alert")
public record EvidenceView(
        Long transactionId,
        @Schema(example = "TXN_0000042") String externalTxnId,
        @Schema(example = "STRUCTURING_LEG") String evidenceRole,
        BigDecimal baseAmount,
        Instant occurredAt,
        String note) {

    public static EvidenceView from(AlertEvidence evidence) {
        return new EvidenceView(
                evidence.getTransaction() == null ? null : evidence.getTransaction().getId(),
                evidence.getExternalTxnId(),
                evidence.getEvidenceRole(),
                evidence.getBaseAmount(),
                evidence.getOccurredAt(),
                evidence.getNote());
    }
}
