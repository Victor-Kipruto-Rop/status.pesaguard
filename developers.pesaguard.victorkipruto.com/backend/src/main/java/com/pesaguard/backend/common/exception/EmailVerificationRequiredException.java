package com.pesaguard.backend.common.exception;

import java.time.Instant;

import org.springframework.http.HttpStatus;

/** Passwords were correct; email verification is the only remaining login gate. */
public class EmailVerificationRequiredException extends BusinessException {

    private final Instant expiresAt;
    private final Instant resendAvailableAt;
    private final String email;

    public EmailVerificationRequiredException(Instant expiresAt, Instant resendAvailableAt) {
        this(expiresAt, resendAvailableAt, null);
    }

    public EmailVerificationRequiredException(Instant expiresAt, Instant resendAvailableAt, String email) {
        super(HttpStatus.FORBIDDEN, "EMAIL_NOT_VERIFIED",
                "A verification code is required before you can sign in. Enter the code sent to your email.");
        this.expiresAt = expiresAt;
        this.resendAvailableAt = resendAvailableAt;
        this.email = email;
    }

    public Instant verificationExpiresAt() {
        return expiresAt;
    }

    public Instant verificationResendAvailableAt() {
        return resendAvailableAt;
    }

    public String verificationEmail() {
        return email;
    }
}
