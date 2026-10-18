package com.pesaguard.backend.security.authentication;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body for {@code POST /api/v1/auth/forgot-password}.
 *
 * <p>The response is identical whether or not the address is registered. That is
 * the whole point of this request shape: anything else turns the endpoint into an
 * account-enumeration oracle.
 */
public record ForgotPasswordRequest(
        @NotBlank @Email @Size(max = 320) String email) {
}