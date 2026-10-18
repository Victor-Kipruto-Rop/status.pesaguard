package com.pesaguard.backend.oauth.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Locale;

/**
 * PKCE (RFC 7636) with the S256 challenge method only.
 *
 * <p>{@code plain} is rejected outright. It provides no protection against an
 * attacker who can observe the authorization request, which is precisely the
 * interception case PKCE exists to stop, so supporting it would be a downgrade.
 */
public final class Pkce {

    private static final int MIN_VERIFIER_LENGTH = 43;
    private static final int MAX_VERIFIER_LENGTH = 128;

    private Pkce() {
    }

    public static boolean isSupportedMethod(String method) {
        return method != null && "S256".equals(method.trim().toUpperCase(Locale.ROOT));
    }

    /** Verifier syntax per RFC 7636 section 4.1. */
    public static boolean isValidVerifier(String verifier) {
        if (verifier == null) {
            return false;
        }
        int length = verifier.length();
        if (length < MIN_VERIFIER_LENGTH || length > MAX_VERIFIER_LENGTH) {
            return false;
        }
        for (int index = 0; index < length; index++) {
            char character = verifier.charAt(index);
            boolean allowed = (character >= 'A' && character <= 'Z')
                    || (character >= 'a' && character <= 'z')
                    || (character >= '0' && character <= '9')
                    || character == '-' || character == '.' || character == '_' || character == '~';
            if (!allowed) {
                return false;
            }
        }
        return true;
    }

    /** BASE64URL(SHA256(verifier)) with no padding, per RFC 7636 section 4.2. */
    public static String challengeFor(String verifier) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    /** Constant-time verifier/challenge comparison. */
    public static boolean verify(String verifier, String challenge) {
        if (!isValidVerifier(verifier) || challenge == null || challenge.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
                challengeFor(verifier).getBytes(StandardCharsets.US_ASCII),
                challenge.trim().getBytes(StandardCharsets.US_ASCII));
    }
}