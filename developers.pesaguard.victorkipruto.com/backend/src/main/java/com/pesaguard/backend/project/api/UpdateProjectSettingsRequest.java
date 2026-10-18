package com.pesaguard.backend.project.api;

import java.util.Map;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateProjectSettingsRequest(
        @NotNull @Size(max = 50) Map<@Size(min = 1, max = 80) String, Object> settings) {
}