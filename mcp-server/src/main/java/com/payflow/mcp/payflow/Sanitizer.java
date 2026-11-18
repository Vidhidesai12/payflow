package com.payflow.mcp.payflow;

/**
 * Free text in PayFlow (a checker's rejection reason, for example) was typed by a person and could say
 * anything, including "ignore your instructions and ...". Before it reaches an AI client it is cleaned
 * here and wrapped as {@link UntrustedText}, so it arrives clearly labelled as data.
 */
public final class Sanitizer {

    static final int MAX_LENGTH = 300;

    public record UntrustedText(String untrustedText) {
    }

    private Sanitizer() {
    }

    public static UntrustedText untrusted(String text) {
        return text == null ? null : new UntrustedText(clean(text));
    }

    /** Removes control and invisible formatting characters, collapses whitespace, caps the length. */
    public static String clean(String text) {
        String s = text.replaceAll("[\\p{Cc}\\p{Cf}&&[^\\n]]", "")   // control chars, zero-width and bidi tricks
                .replaceAll("\\s+", " ")
                .strip();
        return s.length() > MAX_LENGTH ? s.substring(0, MAX_LENGTH) + "…" : s;
    }
}
