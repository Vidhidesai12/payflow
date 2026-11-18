package com.payflow.mcp.payflow;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/**
 * Where PayFlow runs, and the service account this server uses there. The account (mcp-agent) has the
 * MAKER and AUDITOR roles in payment-service and never CHECKER, so it cannot approve payments even if
 * someone added an approve tool here.
 */
@ConfigurationProperties("payflow")
public record PayFlowProperties(String paymentsBaseUrl, String ledgerBaseUrl, String serviceUser,
                                String servicePassword, BigDecimal agentPaymentLimit, boolean requireHumanConfirmation) {
}
