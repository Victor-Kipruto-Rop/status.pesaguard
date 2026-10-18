package com.pesaguard.backend.security.authentication;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body for {@code POST /api/v1/auth/reset-password}. */
public record ResetPasswordRequest(
        @NotBlank @Size(max = 512) String token,
        @NotBlank @Size(max = 200) String newPassword) {
}