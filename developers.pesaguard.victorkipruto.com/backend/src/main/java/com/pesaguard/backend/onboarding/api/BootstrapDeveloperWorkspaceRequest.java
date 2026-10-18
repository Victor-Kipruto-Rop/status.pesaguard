package com.pesaguard.backend.onboarding.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record BootstrapDeveloperWorkspaceRequest(
        @NotBlank @Size(min = 2, max = 120) String projectName,
        @NotBlank @Pattern(regexp = "payments|events|risk") String template) {
}
