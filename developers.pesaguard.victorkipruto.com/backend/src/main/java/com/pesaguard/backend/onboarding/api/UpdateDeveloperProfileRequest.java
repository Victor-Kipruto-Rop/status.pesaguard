package com.pesaguard.backend.onboarding.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateDeveloperProfileRequest(
        @NotBlank @Size(min = 2, max = 120) String displayName) {
}
