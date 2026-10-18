package com.pesaguard.backend.webhooks.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Produces and verifies PesaGuard webhook signatures.
 *
 * <p>The header a receiver is given looks like:
 *
 * <pre>
 *   t=1712345678,v1=9f2c...ab
 * </pre>
 *
 * <p>The signature covers the timestamp <em>and</em> the body, not the body alone.
 * That is the whole point: a signature over the body alone is a bearer token for
 * "this payload is genuine", and an attacker who captures one delivery can replay
 * it forever. Binding the timestamp means a captured delivery stops verifying once
 * it falls outside the tolerance window.
 *
 * <p>Versions exist so the scheme can change. {@code v1} is HMAC-SHA256 over
 * {@code "{version}.{timestamp}.{body}"}. A future {@code v2} can be introduced
 * alongside it without invalidating receivers still on v1, and the version travels
 * in the header so the receiver knows which rule to apply.
 */
public final class WebhookSignature {

    public static final String HEADER = "PesaGuard-Signature";
    public static final String VERSION_PREFIX = "v1=";
    public static final String CURRENT_VERSION = "v1";

    /**
     * How far a timestamp may drift. Five minutes is the usual choice: wide
     * enough for clock skew between hosts, narrow enough that a captured
     * signature has a small window.
     */
    public static final Duration DEFAULT_TOLERANCE = Duration.ofMinutes(5);

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private WebhookSignature() {
    }

    /** Builds the signature header for a body at a given time. */
    public static String sign(String secret, String body, Instant timestamp) {
        return sign(secret, body, timestamp, CURRENT_VERSION);
    }

    public static String sign(String secret, String body, Instant timestamp, String version) {
        long epochSeconds = timestamp.getEpochSecond();
        String signature = hmacHex(secret, signedPayload(version, epochSeconds, body));
        return "t=" + epochSeconds + "," + version + "=" + signature;
    }

    /**
     * The exact bytes that are signed.
     *
     * <p>Version first, then a separator that cannot appear in any of the parts,
     * then timestamp, then body. The encoding is unambiguous, so the receiver
     * never has to guess how to split it.
     */
    static String signedPayload(String version, long epochSeconds, String body) {
        return version + "." + epochSeconds + "." + (body == null ? "" : body);
    }

    public static String hmacHex(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return toHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 is required but unavailable", exception);
        }
    }

    private static String toHex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int index = 0; index < bytes.length; index++) {
            int value = bytes[index] & 0xff;
            out[index * 2] = HEX[value >>> 4];
            out[index * 2 + 1] = HEX[value & 0x0f];
        }
        return new String(out);
    }


/**
     * Verifies a signature header against a body.
     *
     * <p>Two independent checks, and both must pass:
     * <ul>
     *   <li><b>Freshness.</b> The timestamp must be within {@code tolerance} of
     *       now. This is what makes replay detectable.</li>
     *   <li><b>Authenticity.</b> The HMAC must match, compared in constant time so
     *       a wrong signature cannot be discovered one byte at a time.</li>
     * </ul>
     *
     * <p>Returns a {@link VerificationResult} rather than a boolean so a receiver
     * can tell "your clock is wrong" from "this signature is forged". Both fail;
     * conflating them makes incident triage worse.
     */
    public static VerificationResult verify(String secret, String header, String body, Instant now,
            Duration tolerance) {
        if (header == null || header.isBlank()) {
            return VerificationResult.MISSING_SIGNATURE;
        }
        Long timestamp = null;
        String expected = null;
        for (String part : header.split(",")) {
            String trimmed = part.trim();
            if (trimmed.startsWith("t=")) {
                try {
                    timestamp = Long.parseLong(trimmed.substring(2));
                } catch (NumberFormatException exception) {
                    return VerificationResult.MALFORMED;
                }
            } else if (trimmed.startsWith(VERSION_PREFIX)) {
                expected = trimmed.substring(VERSION_PREFIX.length());
            }
        }
        if (timestamp == null || expected == null || expected.isBlank()) {
            return VerificationResult.MALFORMED;
        }
        Duration window = tolerance == null ? DEFAULT_TOLERANCE : tolerance;
        // Absolute drift. A timestamp far in the future is as suspicious as one
        // far in the past, and an "older than" check alone would miss it.
        long drift = Math.abs(now.getEpochSecond() - timestamp);
        if (drift > window.getSeconds()) {
            return VerificationResult.STALE;
        }
        String candidate = hmacHex(secret, signedPayload(CURRENT_VERSION, timestamp, body));
        if (!constantTimeEquals(candidate, expected)) {
            return VerificationResult.INVALID_SIGNATURE;
        }
        return VerificationResult.VALID;
    }

    public static VerificationResult verify(String secret, String header, String body, Clock clock) {
        return verify(secret, header, body, clock.instant(), DEFAULT_TOLERANCE);
    }

    /**
     * Constant-time comparison.
     *
     * <p>{@code String.equals} short-circuits on the first differing byte, which
     * leaks how much of a guessed signature was correct and turns brute force from
     * a 2^128 search into a byte-at-a-time oracle.
     */
    static boolean constantTimeEquals(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8),
                right.getBytes(StandardCharsets.UTF_8));
    }

    /** Why a verification passed or failed. */
    public enum VerificationResult {
        VALID("valid"),
        MISSING_SIGNATURE("no signature header"),
        MALFORMED("signature header could not be parsed"),
        /** Outside the tolerance window. This is what a replay looks like. */
        STALE("timestamp outside the accepted window"),
        INVALID_SIGNATURE("signature does not match");

        private final String description;

        VerificationResult(String description) {
            this.description = description;
        }

        public String description() {
            return description;
        }

        public boolean isValid() {
            return this == VALID;
        }

        @Override
        public String toString() {
            return name().toLowerCase(Locale.ROOT);
        }
    }
}
