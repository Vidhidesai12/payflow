package com.payflow.mcp.tools;

import com.payflow.mcp.audit.AuditLog;

/** A tool call that did not succeed. The message is returned to the AI client, so it must be safe. */
public class ToolException extends RuntimeException {

    private final AuditLog.Outcome outcome;

    public ToolException(AuditLog.Outcome outcome, String message) {
        super(message);
        this.outcome = outcome;
    }

    public AuditLog.Outcome outcome() {
        return outcome;
    }
}
