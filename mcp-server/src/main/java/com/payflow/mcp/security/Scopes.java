package com.payflow.mcp.security;

/** Permission scopes a client token can carry. Each tool requires exactly one. */
public final class Scopes {

    public static final String PAYMENTS_READ = "payments:read";
    public static final String PAYMENTS_CREATE = "payments:create";
    public static final String LEDGER_READ = "ledger:read";
    public static final String RECONCILIATION_READ = "reconciliation:read";

    private Scopes() {
    }
}
