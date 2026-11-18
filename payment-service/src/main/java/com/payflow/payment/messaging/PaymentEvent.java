package com.payflow.payment.messaging;

import com.payflow.payment.domain.Payment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Published on every payment status change. Carries the full money details so consumers
 * (the ledger) never need to call back into payment-service.
 */
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

    static PaymentEvent of(Payment p, Instant now) {
        String type = switch (p.getStatus()) {
            case CREATED -> "PaymentCreated";
            case PENDING_APPROVAL -> "PaymentHeld";
            case APPROVED -> "PaymentApproved";
            case SETTLED -> "PaymentSettled";
            case FAILED -> "PaymentFailed";
        };
        return new PaymentEvent(UUID.randomUUID(), type, p.getId(), p.getDebtorAccount(), p.getCreditorAccount(),
                p.getAmount(), p.getCurrency(), p.getStatus().name(), now);
    }
}
