package com.payflow.fraud;

public record FraudCheckResponse(Decision decision, String reason) {

    public enum Decision {
        /** Low risk: carry on. */
        ALLOW,
        /** Suspicious: a human must look at it before money moves. */
        REVIEW,
        /** High risk: refuse the payment. */
        DENY
    }
}
