package com.pesaguard.backend.onboarding.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateOnboardingOrganizationRequest(
        @NotBlank @Size(min = 2, max = 120) String name,
        @NotBlank @Size(min = 10, max = 500) String description) {
}
