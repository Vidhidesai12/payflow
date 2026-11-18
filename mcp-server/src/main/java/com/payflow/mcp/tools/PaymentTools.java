package com.payflow.mcp.tools;

import com.payflow.mcp.audit.AuditLog;
import com.payflow.mcp.payflow.PayFlowClient;
import com.payflow.mcp.payflow.PayFlowProperties;
import com.payflow.mcp.payflow.Sanitizer;
import com.payflow.mcp.security.Scopes;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;
import org.springframework.ai.mcp.annotation.context.StructuredElicitResult;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Payment tools. Note what is missing: there is no approve, reject or settle tool. An AI client can
 * read payments and, with the right scope and a person's confirmation, create small ones. Approving
 * stays a human CHECKER's job inside PayFlow (maker-checker), whatever a prompt says.
 */
@Component
public class PaymentTools {

    public record PaymentView(UUID id, String debtorAccount, String creditorAccount, BigDecimal amount,
                              String currency, String status, Sanitizer.UntrustedText failureReason,
                              Sanitizer.UntrustedText holdReason, String createdBy, String approvedBy,
                              Instant createdAt, Instant settledAt) {

        static PaymentView of(PayFlowClient.Payment p) {
            return new PaymentView(p.id(), p.debtorAccount(), p.creditorAccount(), p.amount(), p.currency(),
                    p.status(), Sanitizer.untrusted(p.failureReason()), Sanitizer.untrusted(p.holdReason()),
                    p.createdBy(), p.approvedBy(), p.createdAt(), p.settledAt());
        }
    }

    public record CreateResult(PaymentView payment, boolean alreadyExisted, String nextStep) {
    }

    /** The form the person sees in their AI client before a payment is created. */
    public record ConfirmPayment(boolean confirm) {
    }

    private static final Set<String> STATUSES = Set.of("CREATED", "PENDING_APPROVAL", "APPROVED", "SETTLED", "FAILED");
    private static final Pattern ACCOUNT = Pattern.compile("[A-Z0-9]{6,34}");
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9._:-]{1,80}");

    private final PayFlowClient payflow;
    private final ToolCalls calls;
    private final PayFlowProperties props;

    PaymentTools(PayFlowClient payflow, ToolCalls calls, PayFlowProperties props) {
        this.payflow = payflow;
        this.calls = calls;
        this.props = props;
    }

    @McpTool(name = "get_payment", title = "Get a payment",
            description = "Look up one PayFlow payment by its ID: amount, accounts, status, and why it is held or failed.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false))
    public McpSchema.CallToolResult getPayment(McpSyncRequestContext ctx,
                                      @McpToolParam(description = "Payment ID (UUID)", required = true) String paymentId) {
        return calls.run(ctx, "get_payment", Scopes.PAYMENTS_READ, Map.of("paymentId", String.valueOf(paymentId)),
                () -> PaymentView.of(payflow.payment(uuid(paymentId))));
    }

    @McpTool(name = "list_payments", title = "List recent payments",
            description = "List the most recent PayFlow payments, newest first, optionally filtered by status "
                    + "(CREATED, PENDING_APPROVAL, APPROVED, SETTLED, FAILED).",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false))
    public McpSchema.CallToolResult listPayments(McpSyncRequestContext ctx,
                                        @McpToolParam(description = "Optional status filter", required = false) String status,
                                        @McpToolParam(description = "How many to return, 1-50 (default 20)", required = false) Integer limit) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("status", status);
        args.put("limit", limit);
        return calls.run(ctx, "list_payments", Scopes.PAYMENTS_READ, args, () -> {
            String s = status == null || status.isBlank() ? null : status.trim().toUpperCase();
            if (s != null && !STATUSES.contains(s)) {
                throw new ToolException(AuditLog.Outcome.REFUSED, "status must be one of " + STATUSES);
            }
            return payflow.payments(s).stream().limit(limit(limit)).map(PaymentView::of).toList();
        });
    }

    @McpTool(name = "list_held_payments", title = "List payments waiting for approval",
            description = "List payments waiting for a human checker, with the reason each one is held "
                    + "(above the approval threshold, flagged by the fraud check, or fraud check unavailable).",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false))
    public McpSchema.CallToolResult listHeldPayments(McpSyncRequestContext ctx,
                                            @McpToolParam(description = "How many to return, 1-50 (default 20)", required = false) Integer limit) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("limit", limit);
        return calls.run(ctx, "list_held_payments", Scopes.PAYMENTS_READ, args,
                () -> payflow.payments("PENDING_APPROVAL").stream().limit(limit(limit)).map(PaymentView::of).toList());
    }

    @McpTool(name = "create_payment", title = "Create a payment (asks a person first)",
            description = "Create a payment in PayFlow. Before anything is created, the person using this client is "
                    + "asked to confirm the exact amount and accounts. Payments above the agent limit are refused. "
                    + "Reuse the same idempotencyKey when retrying, so a retry never creates a second payment. "
                    + "Large payments still wait for a human checker; this server cannot approve payments.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false))
    public McpSchema.CallToolResult createPayment(McpSyncRequestContext ctx,
                                         @McpToolParam(description = "Debtor (paying) account, 6-34 uppercase letters or digits", required = true) String debtorAccount,
                                         @McpToolParam(description = "Creditor (receiving) account, 6-34 uppercase letters or digits", required = true) String creditorAccount,
                                         @McpToolParam(description = "Amount as a decimal string with at most 2 decimals, e.g. \"150.00\"", required = true) String amount,
                                         @McpToolParam(description = "3-letter ISO currency code, e.g. USD", required = true) String currency,
                                         @McpToolParam(description = "Unique key for this payment request (1-80 chars); reuse it on retries", required = true) String idempotencyKey) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("debtorAccount", debtorAccount);
        args.put("creditorAccount", creditorAccount);
        args.put("amount", amount);
        args.put("currency", currency);
        args.put("idempotencyKey", idempotencyKey);
        return calls.run(ctx, "create_payment", Scopes.PAYMENTS_CREATE, args, () -> {
            PayFlowClient.NewPayment payment = validate(debtorAccount, creditorAccount, amount, currency, idempotencyKey);

            if (payment.amount().compareTo(props.agentPaymentLimit()) > 0) {
                throw new ToolException(AuditLog.Outcome.REFUSED, "AI clients may create payments up to "
                        + props.agentPaymentLimit() + "; a person must enter larger payments in PayFlow directly");
            }
            if (props.requireHumanConfirmation()) {
                confirmWithPerson(ctx, payment);
            }

            // Keys are scoped per client, so two AI clients that pick the same key can't collide.
            String key = ToolCalls.caller(ctx).client() + ":" + idempotencyKey;
            PayFlowClient.Created created = payflow.create(key, payment);
            PaymentView view = PaymentView.of(created.payment());
            String next = switch (String.valueOf(view.status())) {
                case "PENDING_APPROVAL" -> "Held for a human checker in PayFlow. This server cannot approve payments.";
                case "FAILED" -> "PayFlow refused the payment; see failureReason.";
                default -> "Approved by PayFlow's rules; the ledger will book it and it will become SETTLED.";
            };
            return new CreateResult(view, created.replayed(), next);
        });
    }

    /** Asks the human through the AI client (MCP elicitation). No confirmation, no payment. */
    private static void confirmWithPerson(McpSyncRequestContext ctx, PayFlowClient.NewPayment p) {
        if (ctx == null || !ctx.elicitEnabled()) {
            throw new ToolException(AuditLog.Outcome.REFUSED, "This client cannot ask a person to confirm payments, "
                    + "so no payment was created. Use a client that supports MCP elicitation, or PayFlow directly.");
        }
        StructuredElicitResult<ConfirmPayment> answer = ctx.elicit(spec -> spec.message(
                "Create a payment of %s %s from %s to %s? Check the amount and both accounts."
                        .formatted(p.amount().toPlainString(), p.currency(), p.debtorAccount(), p.creditorAccount())),
                ConfirmPayment.class);
        boolean confirmed = answer != null && answer.action() == McpSchema.ElicitResult.Action.ACCEPT
                && answer.structuredContent() != null && answer.structuredContent().confirm();
        if (!confirmed) {
            throw new ToolException(AuditLog.Outcome.REFUSED, "The person did not confirm, so no payment was created");
        }
    }

    private static PayFlowClient.NewPayment validate(String debtor, String creditor, String amount, String currency,
                                                     String key) {
        if (debtor == null || !ACCOUNT.matcher(debtor).matches() || creditor == null || !ACCOUNT.matcher(creditor).matches()) {
            throw new ToolException(AuditLog.Outcome.REFUSED, "Accounts must be 6-34 uppercase letters or digits");
        }
        if (debtor.equals(creditor)) {
            throw new ToolException(AuditLog.Outcome.REFUSED, "Debtor and creditor must be different accounts");
        }
        if (currency == null || !CURRENCY.matcher(currency).matches()) {
            throw new ToolException(AuditLog.Outcome.REFUSED, "currency must be a 3-letter ISO code such as USD");
        }
        if (key == null || !KEY.matcher(key).matches()) {
            throw new ToolException(AuditLog.Outcome.REFUSED, "idempotencyKey must be 1-80 letters, digits, '.', '_', ':' or '-'");
        }
        BigDecimal value;
        try {
            value = new BigDecimal(amount == null ? "" : amount.trim());
        } catch (NumberFormatException e) {
            throw new ToolException(AuditLog.Outcome.REFUSED, "amount must be a decimal number such as \"150.00\"");
        }
        if (value.signum() <= 0 || value.stripTrailingZeros().scale() > 2) {
            throw new ToolException(AuditLog.Outcome.REFUSED, "amount must be positive with at most 2 decimals");
        }
        return new PayFlowClient.NewPayment(debtor, creditor, value, currency);
    }

    static UUID uuid(String id) {
        try {
            return UUID.fromString(id == null ? "" : id.trim());
        } catch (IllegalArgumentException e) {
            throw new ToolException(AuditLog.Outcome.REFUSED, "paymentId must be a UUID");
        }
    }

    private static int limit(Integer limit) {
        return limit == null ? 20 : Math.max(1, Math.min(50, limit));
    }

    static List<String> statuses() {
        return List.copyOf(STATUSES);
    }
}
