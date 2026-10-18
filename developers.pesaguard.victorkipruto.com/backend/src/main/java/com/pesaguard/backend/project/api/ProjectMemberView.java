package com.pesaguard.backend.project.api;

import java.time.Instant;
import java.util.UUID;

import com.pesaguard.backend.project.domain.ProjectMemberRole;
import com.pesaguard.backend.project.domain.ProjectMemberStatus;

public record ProjectMemberView(
        UUID id,
        UUID projectId,
        UUID userId,
        String email,
        String displayName,
        ProjectMemberRole role,
        ProjectMemberStatus status,
        Instant createdAt,
        Instant updatedAt) {
}