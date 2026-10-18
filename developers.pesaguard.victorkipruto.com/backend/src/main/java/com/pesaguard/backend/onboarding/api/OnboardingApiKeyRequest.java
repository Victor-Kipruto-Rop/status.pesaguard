package com.pesaguard.backend.onboarding.api;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

public record OnboardingApiKeyRequest(
        @NotNull UUID projectId,
        @NotNull UUID environmentId,
        @NotNull UUID idempotencyKey) {
}
