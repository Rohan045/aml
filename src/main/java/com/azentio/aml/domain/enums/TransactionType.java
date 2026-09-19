package com.azentio.aml.domain.enums;

public enum TransactionType {
    CASH_DEPOSIT,
    CASH_WITHDRAWAL,
    TRANSFER_IN,
    TRANSFER_OUT,
    WIRE_IN,
    WIRE_OUT,
    CHEQUE_DEPOSIT,
    CHEQUE_WITHDRAWAL,
    CARD_PAYMENT,
    BILL_PAYMENT,
    LOAN_DISBURSEMENT,
    LOAN_REPAYMENT,
    INTEREST_CREDIT,
    FEE_DEBIT,
    REVERSAL,
    OTHER;

    /** Inbound value used by the rapid-movement (layering) rule. */
    public boolean isInflow() {
        return this == CASH_DEPOSIT
                || this == TRANSFER_IN
                || this == WIRE_IN
                || this == CHEQUE_DEPOSIT
                || this == LOAN_DISBURSEMENT
                || this == INTEREST_CREDIT;
    }

    /** Outbound value used by the rapid-movement (layering) rule. */
    public boolean isOutflow() {
        return this == CASH_WITHDRAWAL
                || this == TRANSFER_OUT
                || this == WIRE_OUT
                || this == CHEQUE_WITHDRAWAL
                || this == CARD_PAYMENT
                || this == BILL_PAYMENT
                || this == LOAN_REPAYMENT
                || this == FEE_DEBIT;
    }
}
