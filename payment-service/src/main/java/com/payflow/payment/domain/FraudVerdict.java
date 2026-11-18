package com.payflow.payment.domain;

/** The outcome of the fraud check, as far as routing a payment is concerned. */
public enum FraudVerdict {
    /** Low risk. */
    ALLOW,
    /** Suspicious: a human must look before money moves. */
    REVIEW,
    /** High risk: refuse. */
    DENY,
    /** The fraud service could not answer (down, slow, circuit open). Fail safe: treat like REVIEW. */
    UNAVAILABLE
}
