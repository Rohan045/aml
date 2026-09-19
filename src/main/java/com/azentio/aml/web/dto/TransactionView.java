package com.azentio.aml.web.dto;

import com.azentio.aml.domain.Transaction;
import com.azentio.aml.domain.enums.Channel;
import com.azentio.aml.domain.enums.ScreeningStatus;
import com.azentio.aml.domain.enums.TransactionDirection;
import com.azentio.aml.domain.enums.TransactionStatus;
import com.azentio.aml.domain.enums.TransactionType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * A transaction as rendered on the customer timeline and in alert evidence.
 *
 * <p>Both the original and the base-currency amount are returned: the analyst needs the value the
 * customer actually saw, while the base amount is the one every rule threshold was compared
 * against, and showing only one of them makes an alert explanation impossible to verify.
 */
@Schema(name = "TransactionView", description = "A posted transaction")
public record TransactionView(
        Long id,
        @Schema(example = "TXN_0000001") String externalTxnId,
        String accountId,
        String customerId,
        TransactionDirection direction,
        TransactionType transactionType,
        Channel channel,
        BigDecimal amount,
        @Schema(example = "INR") String currency,
        @Schema(description = "Amount normalised to the platform base currency")
                BigDecimal baseAmount,
        @Schema(example = "USD") String baseCurrency,
        Instant transactionTimestamp,
        TransactionStatus status,
        String narration,
        String counterpartyName,
        String counterpartyAccount,
        String counterpartyBank,
        String counterpartyCountry,
        String originCountry,
        String destinationCountry,
        boolean crossBorder,
        ScreeningStatus screeningStatus) {

    public static TransactionView from(Transaction transaction) {
        return new TransactionView(
                transaction.getId(),
                transaction.getExternalTxnId(),
                transaction.getAccount() == null ? null : transaction.getAccount().getAccountId(),
                transaction.getCustomer() == null
                        ? null
                        : transaction.getCustomer().getCustomerId(),
                transaction.getDirection(),
                transaction.getTransactionType(),
                transaction.getChannel(),
                transaction.getAmount(),
                transaction.getCurrency(),
                transaction.getBaseAmount(),
                transaction.getBaseCurrency(),
                transaction.getTransactionTimestamp(),
                transaction.getStatus(),
                transaction.getNarration(),
                transaction.getCounterpartyName(),
                transaction.getCounterpartyAccount(),
                transaction.getCounterpartyBank(),
                transaction.getCounterpartyCountry(),
                transaction.getOriginCountry(),
                transaction.getDestinationCountry(),
                Boolean.TRUE.equals(transaction.getCrossBorder()),
                transaction.getScreeningStatus());
    }
}
