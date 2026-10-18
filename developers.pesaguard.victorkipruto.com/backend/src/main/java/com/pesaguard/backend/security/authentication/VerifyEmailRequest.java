package com.pesaguard.backend.security.authentication;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

/** Body for {@code POST /api/v1/auth/verify-email}. */
public record VerifyEmailRequest(
        @Email @Size(max = 320) String email,
        @Size(min = 6, max = 6) String code,
        @Size(max = 512) String token) {

    public VerifyEmailRequest(String token) {
        this(null, null, token);
    }
}
