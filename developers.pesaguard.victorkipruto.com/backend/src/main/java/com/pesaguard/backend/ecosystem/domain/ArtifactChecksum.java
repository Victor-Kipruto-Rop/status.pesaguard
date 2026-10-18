package com.pesaguard.backend.ecosystem.domain;

import java.security.MessageDigest;
import java.util.Locale;

/**
 * Verification of published artefact checksums.
 *
 * <p>A download registry whose checksums are wrong is worse than one with no
 * checksums. A developer who cannot tell a corrupted download from a tampered
 * one has no way to know whether the code they just installed is what the
 * maintainers published. So checksums are validated for shape on the way in, and
 * compared in constant time on the way out.
 *
 * <p>SHA-256 throughout. A weaker digest would be the wrong place to economise,
 * since this is the one value standing between a developer and an attacker who
 * can write to a package mirror.
 */
public final class ArtifactChecksum {

    private static final int SHA_256_HEX_LENGTH = 64;
    private static final String ALGORITHM = "SHA-256";

    private ArtifactChecksum() {
    }

    /**
     * Whether a string is a syntactically valid SHA-256 hex digest.
     *
     * <p>Case-insensitive: published checksums appear in both upper and lower
     * case depending on the tool that generated them, and rejecting an uppercase
     * digest would reject a correct one.
     */
    public static boolean isValid(String checksum) {
        if (checksum == null || checksum.length() != SHA_256_HEX_LENGTH) {
            return false;
        }
        for (int index = 0; index < checksum.length(); index++) {
            char character = checksum.charAt(index);
            boolean hex = (character >= '0' && character <= '9')
                    || (character >= 'a' && character <= 'f')
                    || (character >= 'A' && character <= 'F');
            if (!hex) {
                return false;
            }
        }
        return true;
    }

    /**
     * Normalises a digest to lower case for storage and comparison.
     *
     * @throws IllegalArgumentException if the value is not a valid digest
     */
    public static String normalise(String checksum) {
        if (!isValid(checksum)) {
            throw new IllegalArgumentException(
                    "A SHA-256 checksum must be 64 hexadecimal characters");
        }
        return checksum.toLowerCase(Locale.ROOT);
    }

    /** Computes the digest of an artefact's bytes. */
    public static String of(byte[] contents) {
        try {
            MessageDigest digest = MessageDigest.getInstance(ALGORITHM);
            return java.util.HexFormat.of().formatHex(digest.digest(contents));
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            // SHA-256 is required of every Java runtime; if it is missing the
            // platform is not fit to publish artefacts at all.
            throw new IllegalStateException("SHA-256 is required but unavailable", unavailable);
        }
    }

    /**
     * Compares two checksums without leaking their contents through timing.
     *
     * <p>Constant time because this comparison is reachable from a public
     * endpoint, and a byte-by-byte early exit would let an attacker recover a
     * digest one character at a time.
     *
     * @return true only if both are valid and equal
     */
    public static boolean matches(String expected, String actual) {
        if (!isValid(expected) || !isValid(actual)) {
            return false;
        }
        return MessageDigest.isEqual(
                normalise(expected).getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                normalise(actual).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }
}