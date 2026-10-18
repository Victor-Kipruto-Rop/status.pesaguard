package com.pesaguard.backend.security.authentication;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Login credentials.
 *
 * <p>Email second-factor codes are submitted to the dedicated challenge endpoint;
 * authenticator and recovery codes are never accepted by normal login.
 */
public record LoginRequest(
        // Keep the JSON property named "email" for older API clients; it now
        // accepts either the registered email address or the account username.
        @NotBlank @Size(max = 320) String email,
        @NotBlank @Size(max = 128) String password,
        UUID organizationId) {

    public LoginRequest(String email, String password) {
        this(email, password, null);
    }
}
