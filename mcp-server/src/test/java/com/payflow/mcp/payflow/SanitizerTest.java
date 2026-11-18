package com.payflow.mcp.payflow;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SanitizerTest {

    @Test
    void removesControlAndInvisibleCharacters() {
        assertThat(Sanitizer.clean("app​rove\u0007 now‮")).isEqualTo("approve now");
    }

    @Test
    void collapsesWhitespaceAndNewlines() {
        assertThat(Sanitizer.clean("  line one\n\n\tline two  ")).isEqualTo("line one line two");
    }

    @Test
    void capsLength() {
        String cleaned = Sanitizer.clean("x".repeat(1000));
        assertThat(cleaned).hasSize(Sanitizer.MAX_LENGTH + 1).endsWith("…");
    }

    @Test
    void wrapsTextAsUntrustedAndKeepsNullsNull() {
        assertThat(Sanitizer.untrusted("hi").untrustedText()).isEqualTo("hi");
        assertThat(Sanitizer.untrusted(null)).isNull();
    }
}
