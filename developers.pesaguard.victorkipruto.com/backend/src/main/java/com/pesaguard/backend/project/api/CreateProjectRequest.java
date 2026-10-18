package com.pesaguard.backend.project.api;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateProjectRequest(
        @NotBlank @Size(min = 2, max = 120) String name,
        @NotBlank @Size(min = 2, max = 80)
        @Pattern(regexp = "[a-z0-9]+(?:-[a-z0-9]+)*") String slug) {
}
