package com.pesaguard.backend.onboarding.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record UpdateOnboardingProgressRequest(
        @NotBlank @Pattern(regexp = "profile|welcome|organization|project|environment|api-key|first-request|api-explorer|webhook|documentation|production-readiness|complete")
        String currentStep,
        @Pattern(regexp = "welcome|organization|project|environment|api-key|first-request|api-explorer|webhook|documentation|production-readiness")
        String completedStep) {
}
