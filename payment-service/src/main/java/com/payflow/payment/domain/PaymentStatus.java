package com.payflow.payment.domain;

/**
 * Lifecycle of a payment. Allowed moves:
 *
 * <pre>
 * CREATED ──► APPROVED ─────────► SETTLED
 *    │            ▲
 *    └──► PENDING_APPROVAL
 *
 * Any non-final status ──► FAILED
 * </pre>
 *
 * SETTLED and FAILED are final.
 */
public enum PaymentStatus {
    CREATED,
    PENDING_APPROVAL,
    APPROVED,
    SETTLED,
    FAILED;

    public boolean canTransitionTo(PaymentStatus next) {
        return switch (this) {
            case CREATED -> next == PENDING_APPROVAL || next == APPROVED || next == FAILED;
            case PENDING_APPROVAL -> next == APPROVED || next == FAILED;
            case APPROVED -> next == SETTLED || next == FAILED;
            case SETTLED, FAILED -> false;
        };
    }

    public boolean isFinal() {
        return this == SETTLED || this == FAILED;
    }
}
