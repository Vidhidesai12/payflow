package com.payflow.payment.service;

import com.payflow.payment.domain.Payment;

/** @param created false when the request was a retry and we returned the original payment */
public record CreatePaymentResult(Payment payment, boolean created) {
}
