package com.payflow.payment.domain;

/** Maker-checker ("four-eyes") rule: the person who created a payment cannot approve it. */
public class SelfApprovalException extends RuntimeException {

    public SelfApprovalException(String user) {
        super("User '" + user + "' created this payment and cannot also approve it");
    }
}
