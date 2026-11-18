package com.payflow.ledger.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * What payment-service publishes on every status change. The ledger only acts on PaymentApproved.
 * Unknown fields are ignored so payment-service can add fields without breaking this consumer.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PaymentEvent(
        UUID eventId,
        String eventType,
        UUID paymentId,
        String debtorAccount,
        String creditorAccount,
        BigDecimal amount,
        String currency,
        String status,
        Instant occurredAt) {

    public static final String APPROVED = "PaymentApproved";

    /** Throws if a field the ledger needs is missing, so the event goes to the dead-letter topic. */
    void validate() {
        if (eventId == null || eventType == null || paymentId == null) {
            throw new InvalidEventException("eventId, eventType and paymentId are required");
        }
        if (APPROVED.equals(eventType) && (debtorAccount == null || creditorAccount == null
                || amount == null || amount.signum() <= 0 || currency == null)) {
            throw new InvalidEventException("PaymentApproved " + eventId + " is missing money details");
        }
    }
}
