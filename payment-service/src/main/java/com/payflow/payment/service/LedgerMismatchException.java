package com.payflow.payment.service;

import java.util.UUID;

/** The ledger booked a different amount or currency than the payment. Needs a human, not a retry. */
public class LedgerMismatchException extends RuntimeException {

    public LedgerMismatchException(UUID paymentId, String detail) {
        super("Ledger posting for payment " + paymentId + " does not match: " + detail);
    }
}
