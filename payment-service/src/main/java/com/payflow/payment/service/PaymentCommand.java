package com.payflow.payment.service;

import java.math.BigDecimal;

/** Already-validated input for creating a payment. */
public record PaymentCommand(String debtorAccount, String creditorAccount, BigDecimal amount, String currency) {
}
