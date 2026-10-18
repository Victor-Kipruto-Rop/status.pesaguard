package com.pesaguard.backend.security.authentication;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record PasswordChangeVerifyRequest(
        @NotNull UUID challengeId,
        @NotBlank @Pattern(regexp = "\\d{6}") String code,
        @NotBlank @Size(max = 256) String currentPassword,
        @NotBlank @Size(max = 256) String newPassword) {
}
