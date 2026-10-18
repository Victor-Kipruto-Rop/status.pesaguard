package com.pesaguard.backend.security.authentication;

import java.time.Instant;
import java.util.UUID;

import org.springframework.http.HttpStatus;

import com.pesaguard.backend.common.exception.BusinessException;

public class LoginEmailMfaRequiredException extends BusinessException {

    private final UUID challengeId;
    private final Instant expiresAt;
    private final Instant resendAvailableAt;
    private final String maskedEmail;

    public LoginEmailMfaRequiredException(EmailLoginMfaChallenge challenge) {
        super(HttpStatus.UNAUTHORIZED, "LOGIN_EMAIL_MFA_REQUIRED",
                "Enter the verification code sent to your email to finish signing in.");
        this.challengeId = challenge.challengeId();
        this.expiresAt = challenge.expiresAt();
        this.resendAvailableAt = challenge.resendAvailableAt();
        this.maskedEmail = challenge.maskedEmail();
    }

    public UUID challengeId() {
        return challengeId;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public Instant resendAvailableAt() {
        return resendAvailableAt;
    }

    public String maskedEmail() {
        return maskedEmail;
    }
}
