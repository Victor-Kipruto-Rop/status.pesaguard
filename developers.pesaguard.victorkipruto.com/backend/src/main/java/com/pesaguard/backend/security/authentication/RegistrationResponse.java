package com.pesaguard.backend.security.authentication;

import java.time.Instant;

public record RegistrationResponse(
        String email,
        String organizationName,
        boolean verificationRequired,
        Instant verificationExpiresAt,
        Instant verificationResendAvailableAt,
        Instant serverNow,
        String username) {

    public RegistrationResponse(String email, String organizationName, boolean verificationRequired) {
        this(email, organizationName, verificationRequired, null, null, null, null);
    }

    public RegistrationResponse(
            String email, String organizationName, boolean verificationRequired,
            Instant verificationExpiresAt, Instant verificationResendAvailableAt, Instant serverNow) {
        this(email, organizationName, verificationRequired, verificationExpiresAt,
                verificationResendAvailableAt, serverNow, null);
    }
}
