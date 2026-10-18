package com.pesaguard.backend.developerpreferences.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record UpdateAppearanceRequest(
        @NotBlank @Pattern(regexp = "SYSTEM|LIGHT|DARK") String theme,
        @Min(0) long expectedVersion) {
}
