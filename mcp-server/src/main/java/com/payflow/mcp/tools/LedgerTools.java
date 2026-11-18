package com.payflow.mcp.tools;

import com.payflow.mcp.audit.AuditLog;
import com.payflow.mcp.payflow.PayFlowClient;
import com.payflow.mcp.security.Scopes;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.regex.Pattern;

/** Read-only views of the double-entry ledger and of the latest reconciliation run. */
@Component
public class LedgerTools {

    private static final Pattern ACCOUNT = Pattern.compile("[A-Z0-9]{6,34}");

    private final PayFlowClient payflow;
    private final ToolCalls calls;

    LedgerTools(PayFlowClient payflow, ToolCalls calls) {
        this.payflow = payflow;
        this.calls = calls;
    }

    @McpTool(name = "get_ledger_posting", title = "Get a payment's ledger entries",
            description = "Show how the ledger booked a payment: its debit and credit entries and their totals.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false))
    public McpSchema.CallToolResult getLedgerPosting(McpSyncRequestContext ctx,
                                            @McpToolParam(description = "Payment ID (UUID)", required = true) String paymentId) {
        return calls.run(ctx, "get_ledger_posting", Scopes.LEDGER_READ, Map.of("paymentId", String.valueOf(paymentId)),
                () -> payflow.posting(PaymentTools.uuid(paymentId)));
    }

    @McpTool(name = "get_account_balances", title = "Get an account's balances",
            description = "Ledger balance of one account per currency (credits minus debits).",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false))
    public McpSchema.CallToolResult getAccountBalances(McpSyncRequestContext ctx,
                                              @McpToolParam(description = "Account, 6-34 uppercase letters or digits", required = true) String account) {
        return calls.run(ctx, "get_account_balances", Scopes.LEDGER_READ, Map.of("account", String.valueOf(account)), () -> {
            if (account == null || !ACCOUNT.matcher(account).matches()) {
                throw new ToolException(AuditLog.Outcome.REFUSED, "account must be 6-34 uppercase letters or digits");
            }
            return payflow.balances(account);
        });
    }

    @McpTool(name = "get_trial_balance", title = "Get the trial balance",
            description = "Total debits and credits per currency across the whole ledger; they must be equal.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false))
    public McpSchema.CallToolResult getTrialBalance(McpSyncRequestContext ctx) {
        return calls.run(ctx, "get_trial_balance", Scopes.LEDGER_READ, Map.of(), payflow::trialBalance);
    }

    @McpTool(name = "get_latest_reconciliation", title = "Get the latest reconciliation result",
            description = "The most recent check that payments and the ledger agree: status (MATCHED, MISMATCHED, "
                    + "FAILED), counts, and each mismatch found.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false))
    public McpSchema.CallToolResult getLatestReconciliation(McpSyncRequestContext ctx) {
        return calls.run(ctx, "get_latest_reconciliation", Scopes.RECONCILIATION_READ, Map.of(), () -> {
            var runs = payflow.reconciliationRuns();
            if (runs == null || runs.isEmpty()) {
                return Map.of("message", "No reconciliation has run yet");
            }
            return runs.getFirst();
        });
    }
}
