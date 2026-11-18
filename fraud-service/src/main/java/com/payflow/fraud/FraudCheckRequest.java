package com.payflow.fraud;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public record FraudCheckRequest(
        @NotNull UUID paymentId,
        @NotBlank String debtorAccount,
        @NotBlank String creditorAccount,
        @NotNull BigDecimal amount,
        @NotBlank String currency) {
}
