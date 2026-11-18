package com.payflow.payment.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Fingerprints a payment request so a retried key can be checked against the original body. */
final class RequestHasher {

    private RequestHasher() {
    }

    static String hash(PaymentCommand c) {
        // stripTrailingZeros so that 100, 100.0 and 100.00 count as the same amount
        String canonical = String.join("|",
                c.debtorAccount(),
                c.creditorAccount(),
                c.amount().stripTrailingZeros().toPlainString(),
                c.currency());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available in the JDK", e);
        }
    }
}
