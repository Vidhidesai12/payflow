package com.payflow.ledger.messaging;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Tells payment-service that the money has been booked, so the payment can be marked SETTLED. */
public record LedgerPostedEvent(
        UUID eventId,
        String eventType,
        UUID paymentId,
        UUID postingId,
        BigDecimal amount,
        String currency,
        Instant postedAt) {

    public static final String TYPE = "LedgerPosted";
}
