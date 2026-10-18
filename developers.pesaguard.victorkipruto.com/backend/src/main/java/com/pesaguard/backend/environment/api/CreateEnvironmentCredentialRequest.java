package com.pesaguard.backend.environment.api;

import com.pesaguard.backend.environment.domain.EnvironmentCredentialType;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateEnvironmentCredentialRequest(
        @NotBlank @Size(max = 120) String name,
        @NotNull EnvironmentCredentialType type,
        @NotBlank @Size(max = 16384) String secret) {
}
