package com.pesaguard.backend.environment.api;

import com.pesaguard.backend.environment.domain.EnvironmentType;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateEnvironmentRequest(
        @NotBlank @Size(min = 2, max = 80) String name,
        @NotNull EnvironmentType type) {
}
