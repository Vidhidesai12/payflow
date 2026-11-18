package com.payflow.payment.api;

import com.payflow.payment.service.PaymentCommand;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;

public record CreatePaymentRequest(
        @NotNull @Pattern(regexp = "[A-Z0-9]{6,34}", message = "must be 6-34 uppercase letters or digits")
        String debtorAccount,

        @NotNull @Pattern(regexp = "[A-Z0-9]{6,34}", message = "must be 6-34 uppercase letters or digits")
        String creditorAccount,

        @NotNull @DecimalMin(value = "0.01") @Digits(integer = 17, fraction = 2)
        BigDecimal amount,

        @NotNull @Pattern(regexp = "[A-Z]{3}", message = "must be a 3-letter ISO currency code")
        String currency) {

    @AssertTrue(message = "debtorAccount and creditorAccount must be different")
    boolean isDifferentAccounts() {
        return debtorAccount == null || !debtorAccount.equals(creditorAccount);
    }

    PaymentCommand toCommand() {
        return new PaymentCommand(debtorAccount, creditorAccount, amount, currency);
    }
}
