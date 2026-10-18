package com.pesaguard.backend.project.api;

import com.pesaguard.backend.project.domain.ProjectMemberRole;

import jakarta.validation.constraints.NotNull;

public record ChangeProjectMemberRoleRequest(@NotNull ProjectMemberRole role) {
}