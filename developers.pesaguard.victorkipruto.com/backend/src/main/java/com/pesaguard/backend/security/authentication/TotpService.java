package com.pesaguard.backend.security.authentication;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

/**
 * RFC 6238 time-based one-time passwords.
 *
 * <p>Implemented directly on the JDK's {@code HmacSHA1} rather than adding a
 * dependency: the algorithm is a short, fully specified computation, and
 * {@code javax.crypto} is already the vetted implementation this project relies
 * on for every other cryptographic primitive. A third-party library would add a
 * supply-chain surface for no additional guarantee.
 *
 * <p><b>Verified against the RFC 6238 appendix B test vectors</b> in
 * {@code TotpTest}; those vectors are what make this implementation
 * trustworthy, and they must keep passing if this class is ever touched.
 *
 * <p>Secrets are Base32 (RFC 4648), the encoding every mainstream authenticator
 * app expects in its {@code otpauth://} URI.
 */
@Component
public class TotpService {

    private static final String HMAC_ALGORITHM = "HmacSHA1";
    /** RFC 4226 recommends SHA-1 for HOTP; 6 digits is the interoperable default. */
    private static final int DIGITS = 6;
    private static final int SECRET_BYTES = 20;
    private static final int STEP_SECONDS = 30;

    /**
     * How many steps either side of now are accepted.
     *
     * <p>One step of tolerance absorbs ordinary clock drift between server and
     * phone. It is deliberately not wider: each extra step is an extra window in
     * which an observed code is still valid. Replay inside the window is stopped
     * separately by the last-used counter, so tolerance does not weaken that.
     */
    private static final int WINDOW = 1;

    private final SecureRandom secureRandom;
    private final Clock clock;

    public TotpService(SecureRandom secureRandom, Clock clock) {
        this.secureRandom = secureRandom;
        this.clock = clock;
    }

    /** A new Base32 shared secret for enrolment. Returned once, never stored plaintext. */
    public String generateSecret() {
        byte[] secret = new byte[SECRET_BYTES];
        secureRandom.nextBytes(secret);
        return Base32.encode(secret);
    }

    /** The step number for the current instant. */
    public long currentCounter() {
        return clock.instant().getEpochSecond() / STEP_SECONDS;
    }

    /** The step number for a given epoch second, for deterministic verification. */
    public long counterAt(long epochSecond) {
        return epochSecond / STEP_SECONDS;
    }

    /** The code for a specific step. Exposed so the RFC vectors can be asserted. */
    public String codeForStep(String base32Secret, long counter) {
        return codeAt(Base32.decode(base32Secret), counter);
    }

    /**
     * Whether a submitted code is valid for the secret at this moment.
     *
     * @param base32Secret the stored secret
     * @param code the user's six-digit entry
     */
    public boolean verify(String base32Secret, String code) {
        String candidate = normaliseCode(code);
        if (candidate.length() != DIGITS || !candidate.chars().allMatch(Character::isDigit)) {
            return false;
        }
        byte[] secret;
        try {
            secret = Base32.decode(base32Secret);
        } catch (IllegalArgumentException exception) {
            // A secret that cannot be decoded is a stored-data fault, not a user
            // error, so it fails closed rather than throwing to the caller.
            return false;
        }
        long counter = currentCounter();
        for (long offset = -WINDOW; offset <= WINDOW; offset++) {
            if (constantTimeEquals(codeAt(secret, counter + offset), candidate)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The highest step within tolerance that {@code code} matches, or -1.
     *
     * <p>Used to advance the last-used counter. Matching against the newest step
     * is what makes a code fail after first use: replaying it yields a counter no
     * longer greater than the stored one.
     */
    public long matchingCounter(String base32Secret, String code) {
        String candidate = normaliseCode(code);
        if (candidate.length() != DIGITS) {
            return -1;
        }
        byte[] secret;
        try {
            secret = Base32.decode(base32Secret);
        } catch (IllegalArgumentException exception) {
            return -1;
        }
        long counter = currentCounter();
        for (long offset = WINDOW; offset >= -WINDOW; offset--) {
            if (constantTimeEquals(codeAt(secret, counter + offset), candidate)) {
                return counter + offset;
            }
        }
        return -1;
    }
    /** The {@code otpauth://} URI an authenticator app scans. */
    public String provisioningUri(String issuer, String accountLabel, String base32Secret) {
        String encodedIssuer = urlEncode(issuer);
        String encodedLabel = urlEncode(issuer + ":" + accountLabel);
        return "otpauth://totp/" + encodedLabel
                + "?secret=" + base32Secret
                + "&issuer=" + encodedIssuer
                // Stated explicitly so the app cannot pick an incompatible profile.
                + "&algorithm=SHA1&digits=6&period=30";
    }

    private String codeAt(byte[] secret, long counter) {
        byte[] hash = hmac(secret, counter);
        // RFC 4226 dynamic truncation: the low nibble of the last byte selects the
        // 4-byte window to use.
        int offset = hash[hash.length - 1] & 0x0F;
        int binary = ((hash[offset] & 0x7F) << 24)
                | ((hash[offset + 1] & 0xFF) << 16)
                | ((hash[offset + 2] & 0xFF) << 8)
                | (hash[offset + 3] & 0xFF);
        int otp = binary % (int) Math.pow(10, DIGITS);
        return String.format(Locale.ROOT, "%0" + DIGITS + "d", otp);
    }

    private byte[] hmac(byte[] secret, long counter) {
        byte[] message = ByteBuffer.allocate(Long.BYTES).putLong(counter).array();
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            return mac.doFinal(message);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HmacSHA1 is unavailable", exception);
        }
    }

    private String normaliseCode(String code) {
        if (code == null) {
            return "";
        }
        return code.trim().replace(" ", "").replace("-", "");
    }

    /**
     * Compares two codes without an early exit.
     *
     * <p>A timing side channel here would let an attacker recover a valid code
     * one character at a time, so the comparison must not short-circuit.
     */
    private boolean constantTimeEquals(String expected, String actual) {
        return java.security.MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    /** Minimal RFC 4648 Base32. Padding-free, as authenticator apps expect. */
    static final class Base32 {

        private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

        private Base32() {
        }

        static String encode(byte[] data) {
            StringBuilder result = new StringBuilder((data.length * 8 + 4) / 5);
            int buffer = 0;
            int bitsLeft = 0;
            for (byte value : data) {
                buffer = (buffer << 8) | (value & 0xFF);
                bitsLeft += 8;
                while (bitsLeft >= 5) {
                    result.append(ALPHABET.charAt((buffer >> (bitsLeft - 5)) & 0x1F));
                    bitsLeft -= 5;
                }
            }
            if (bitsLeft > 0) {
                result.append(ALPHABET.charAt((buffer << (5 - bitsLeft)) & 0x1F));
            }
            return result.toString();
        }

        static byte[] decode(String encoded) {
            String cleaned = encoded.trim().toUpperCase(Locale.ROOT).replace("=", "");
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            int buffer = 0;
            int bitsLeft = 0;
            for (int index = 0; index < cleaned.length(); index++) {
                int position = ALPHABET.indexOf(cleaned.charAt(index));
                if (position < 0) {
                    throw new IllegalArgumentException("Not a Base32 character");
                }
                buffer = (buffer << 5) | position;
                bitsLeft += 5;
                if (bitsLeft >= 8) {
                    output.write((buffer >> (bitsLeft - 8)) & 0xFF);
                    bitsLeft -= 8;
                }
            }
            return output.toByteArray();
        }
    }
}