package com.pesaguard.backend.project.api;

import java.util.UUID;

import com.pesaguard.backend.project.domain.ProjectMemberRole;

import jakarta.validation.constraints.NotNull;

public record AddProjectMemberRequest(
        @NotNull UUID userId,
        @NotNull ProjectMemberRole role) {
}