package com.payflow.payment.domain;

public class InvalidStatusTransitionException extends RuntimeException {

    public InvalidStatusTransitionException(PaymentStatus from, PaymentStatus to) {
        super("Cannot move a payment from " + from + " to " + to);
    }
}
