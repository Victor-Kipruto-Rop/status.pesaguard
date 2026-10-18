package com.pesaguard.backend.rbac.api;

import java.time.Instant;
import java.util.UUID;

public record RoleAssignmentView(
        UUID id,
        UUID userId,
        String roleName,
        Instant assignedAt,
        Instant revokedAt,
        boolean active) {
}