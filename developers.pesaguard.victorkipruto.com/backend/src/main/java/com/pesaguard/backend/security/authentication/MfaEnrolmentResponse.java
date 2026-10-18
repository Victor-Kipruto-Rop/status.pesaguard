package com.pesaguard.backend.security.authentication;

/**
 * Enrolment material from {@code POST /api/v1/auth/mfa/enroll}.
 *
 * <p>The secret is returned exactly once, at enrolment, together with an
 * {@code otpauth://} URI an authenticator app can scan. After confirmation it
 * exists only as AES-GCM ciphertext and is not recoverable by anyone including
 * the platform, which is why a user who loses their recovery codes has to
 * re-enrol rather than ask for a copy.
 *
 * @param secret Base32 shared secret
 * @param provisioningUri {@code otpauth://} URI containing the secret
 */
public record MfaEnrolmentResponse(String secret, String provisioningUri) {

    /** The scan URI only, for a client that persists the secret separately. */
    public MfaEnrolmentResponse(String secret) {
        this(secret, null);
    }
}