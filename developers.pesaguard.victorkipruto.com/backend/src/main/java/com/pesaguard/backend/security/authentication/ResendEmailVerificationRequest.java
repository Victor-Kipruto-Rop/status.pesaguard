package com.pesaguard.backend.security.authentication;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResendEmailVerificationRequest(
        @NotBlank @Email @Size(max = 320) String email) {
}
