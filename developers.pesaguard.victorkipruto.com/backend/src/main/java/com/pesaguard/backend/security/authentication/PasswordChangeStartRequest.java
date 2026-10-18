package com.pesaguard.backend.security.authentication;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PasswordChangeStartRequest(
        @NotBlank @Size(max = 256) String currentPassword,
        @NotBlank @Size(max = 256) String newPassword) {
}
