package com.payflow.mcp.payflow;

/** A failure talking to PayFlow, with a message that is safe to show to an AI client. */
public class PayFlowException extends RuntimeException {

    public enum Kind { NOT_FOUND, REJECTED, UNAVAILABLE }

    private final Kind kind;

    public PayFlowException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
