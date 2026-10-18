package com.pesaguard.backend.rbac.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RoleView(
        UUID id,
        String name,
        String description,
        List<String> permissions,
        String kind,
        String status,
        Instant updatedAt) {
}