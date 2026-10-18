package com.pesaguard.backend.security.authentication;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CompleteRegistrationVerificationRequest(
        @NotBlank @Email @Size(max = 320) String email,
        @NotBlank @Pattern(regexp = "\\d{6}") String code,
        @NotBlank @Size(max = 128) String password) {
}
