package com.payflow.payment.api;

import com.payflow.payment.domain.Payment;
import com.payflow.payment.domain.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentResponse(
        UUID id,
        String debtorAccount,
        String creditorAccount,
        BigDecimal amount,
        String currency,
        PaymentStatus status,
        String failureReason,
        String holdReason,
        String createdBy,
        String approvedBy,
        Instant createdAt,
        Instant updatedAt,
        Instant settledAt) {

    static PaymentResponse from(Payment p) {
        return new PaymentResponse(p.getId(), p.getDebtorAccount(), p.getCreditorAccount(), p.getAmount(),
                p.getCurrency(), p.getStatus(), p.getFailureReason(), p.getHoldReason(), p.getCreatedBy(),
                p.getApprovedBy(), p.getCreatedAt(), p.getUpdatedAt(), p.getSettledAt());
    }
}
