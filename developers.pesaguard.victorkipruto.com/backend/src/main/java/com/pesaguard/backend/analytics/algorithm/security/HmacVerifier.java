package com.pesaguard.backend.analytics.algorithm.security;

import java.nio.charset.StandardCharsets;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HMAC signature verification with constant-time comparison.
 *
 * <p>Provider webhooks are untrusted input, and the only reliable way to know a
 * callback really came from the provider is to check an HMAC signature over the exact
 * bytes received, keyed by a shared secret. This computes that signature and compares
 * it to the one the caller supplied.
 *
 * <p>The comparison is constant-time via {@link java.security.MessageDigest#isEqual},
 * which returns only after examining every byte regardless of where the first
 * difference is. A naive byte-by-byte comparison that short-circuits on the first
 * mismatch leaks, through its timing, how many leading bytes an attacker guessed
 * correctly - enough to forge a signature byte-by-byte. Constant-time comparison
 * closes that channel, and it is non-negotiable for anything verifying a signature.
 *
 * <p>The secret is never logged, never included in an exception, and only ever held
 * as a byte array. The algorithm is fixed at HmacSHA256; using a well-defined,
 * current algorithm avoids the downgrade attacks that negotiating a weaker MAC would
 * invite.
 */
public final class HmacVerifier {

    private static final String ALGORITHM = "HmacSHA256";

    private HmacVerifier() {
    }

    /**
     * Compute the HMAC-SHA256 of {@code payload} under {@code secret}.
     *
     * @return the raw signature bytes
     */
    public static byte[] compute(byte[] payload, byte[] secret) {
        if (payload == null || secret == null) {
            throw new IllegalArgumentException("payload and secret must not be null");
        }
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            return mac.doFinal(payload);
        } catch (Exception e) {
            // Never surface the secret or raw crypto internals in the message.
            throw new IllegalStateException("failed to compute HMAC signature", e);
        }
    }

    /** Convenience overload for a UTF-8 string payload. */
    public static byte[] compute(String payload, byte[] secret) {
        return compute(payload.getBytes(StandardCharsets.UTF_8), secret);
    }

    /**
     * Verify a supplied signature against the recomputed one in constant time.
     *
     * @param payload          the exact bytes that were signed
     * @param secret           the shared secret
     * @param providedHexSignature the hex-encoded signature to check, case-insensitive
     * @return {@code true} only if the signatures match exactly
     */
    public static boolean verify(byte[] payload, byte[] secret, String providedHexSignature) {
        if (providedHexSignature == null) {
            return false;
        }
        byte[] expected = compute(payload, secret);
        byte[] provided = decodeHex(providedHexSignature);
        if (provided == null) {
            // A malformed provided signature is a mismatch, not an error to expose.
            return false;
        }
        // MessageDigest.isEqual is length-independent in timing up to the length check
        // and constant-time over the content, which is the required property here.
        return java.security.MessageDigest.isEqual(expected, provided);
    }

    /** Convenience overload for a UTF-8 string payload. */
    public static boolean verify(String payload, byte[] secret, String providedHexSignature) {
        return verify(payload.getBytes(StandardCharsets.UTF_8), secret, providedHexSignature);
    }

    /**
     * Decode a hex string to bytes.
     *
     * <p>Returns {@code null} on any malformed input rather than throwing, so a bad
     * signature is treated as a failed verification and not as an exception path that
     * behaves differently from a normal mismatch.
     */
    private static byte[] decodeHex(String hex) {
        int length = hex.length();
        if (length % 2 != 0) {
            return null;
        }
        byte[] bytes = new byte[length / 2];
        for (int i = 0; i < length; i += 2) {
            int high = Character.digit(hex.charAt(i), 16);
            int low = Character.digit(hex.charAt(i + 1), 16);
            if (high < 0 || low < 0) {
                return null;
            }
            bytes[i / 2] = (byte) ((high << 4) | low);
        }
        return bytes;
    }

    /** Encode raw bytes as lower-case hex, for logging a fingerprint only, never the secret. */
    public static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
