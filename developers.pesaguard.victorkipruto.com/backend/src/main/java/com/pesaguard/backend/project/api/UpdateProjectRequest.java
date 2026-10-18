package com.pesaguard.backend.project.api;

import java.util.Map;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateProjectRequest(
        @NotBlank @Size(min = 2, max = 120) String name,
        @Size(max = 500) String description,
        @NotNull @Size(max = 50) Map<@Size(min = 1, max = 80) String, Object> metadata) {
}