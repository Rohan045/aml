package com.azentio.aml.service.ingestion;

import com.azentio.aml.domain.enums.Channel;
import com.azentio.aml.domain.enums.TransactionDirection;
import com.azentio.aml.domain.enums.TransactionStatus;
import com.azentio.aml.domain.enums.TransactionType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * A single transaction as supplied by an upstream system (REST, CSV row or Kafka message).
 *
 * <p>Kept separate from the {@link com.azentio.aml.domain.Transaction} entity on purpose: the feed
 * contract must be able to evolve independently of the persistence model, and an externally
 * supplied object should never be able to set internal fields such as the screening status, the
 * base amount or the ingestion batch id.
 *
 * <p>Only the fields the detection rules actually depend on are mandatory. Everything else is
 * optional, because a strict schema on optional enrichment data would reject records that are still
 * perfectly screenable.
 */
public record TransactionPayload(
        @NotBlank @Size(max = 64) String externalTxnId,
        @NotBlank @Size(max = 32) String accountId,
        @Size(max = 32) String customerId,
        @NotNull TransactionDirection direction,
        @NotNull TransactionType transactionType,
        Channel channel,
        @NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal amount,
        @NotBlank @Pattern(regexp = "[A-Za-z]{3}", message = "currency must be a 3-letter ISO code")
                String currency,
        @NotNull Instant transactionTimestamp,
        LocalDate valueDate,
        TransactionStatus status,
        @Size(max = 500) String narration,
        BigDecimal balanceAfter,
        @Size(max = 200) String counterpartyName,
        @Size(max = 64) String counterpartyAccount,
        @Size(max = 200) String counterpartyBank,
        @Size(max = 32) String counterpartyBankCode,
        @Size(max = 2) String counterpartyCountry,
        @Size(max = 2) String originCountry,
        @Size(max = 2) String destinationCountry,
        Boolean crossBorder,
        @Size(max = 8) String merchantCategoryCode,
        @Size(max = 20) String branchCode,
        @Size(max = 100) String deviceId,
        @Size(max = 45) String ipAddress) {

    /**
     * Value date defaults to the booking date, which is what the daily-aggregation rules bucket on.
     */
    public LocalDate effectiveValueDate() {
        return valueDate != null
                ? valueDate
                : transactionTimestamp.atZone(java.time.ZoneOffset.UTC).toLocalDate();
    }
}
