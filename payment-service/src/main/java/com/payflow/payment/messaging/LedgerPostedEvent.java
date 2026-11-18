package com.payflow.payment.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Sent by ledger-service once a payment's money has been booked. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LedgerPostedEvent(
        UUID eventId,
        String eventType,
        UUID paymentId,
        UUID postingId,
        BigDecimal amount,
        String currency,
        Instant postedAt) {

    static final String TYPE = "LedgerPosted";

    void validate() {
        if (eventId == null || paymentId == null || amount == null || currency == null) {
            throw new InvalidEventException("LedgerPosted event is missing required fields");
        }
    }
}
