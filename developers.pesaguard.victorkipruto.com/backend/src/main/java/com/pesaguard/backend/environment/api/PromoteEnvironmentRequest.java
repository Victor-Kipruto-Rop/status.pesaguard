package com.pesaguard.backend.environment.api;

import com.pesaguard.backend.environment.domain.EnvironmentType;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record PromoteEnvironmentRequest(
        @NotNull EnvironmentType targetType,
        @Size(max = 500) String reason) {
}