package com.pesaguard.backend.common.exception;

import java.time.Instant;

import org.springframework.http.HttpStatus;

public final class MfaEnrollmentRequiredException extends BusinessException {

    private final String enrollmentToken;
    private final Instant expiresAt;

    public MfaEnrollmentRequiredException(String enrollmentToken, Instant expiresAt) {
        super(HttpStatus.UNAUTHORIZED, "MFA_ENROLLMENT_REQUIRED",
                "Set up an authenticator to continue signing in.");
        this.enrollmentToken = enrollmentToken;
        this.expiresAt = expiresAt;
    }

    public String enrollmentToken() {
        return enrollmentToken;
    }

    public Instant expiresAt() {
        return expiresAt;
    }
}
