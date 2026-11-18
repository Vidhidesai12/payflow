package com.payflow.payment.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RejectPaymentRequest(@NotBlank @Size(max = 200) String reason) {
}
