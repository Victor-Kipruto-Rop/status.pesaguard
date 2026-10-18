package com.pesaguard.backend.project.api;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.pesaguard.backend.project.domain.ProjectStatus;

public record ProjectView(
        UUID id,
        String name,
        String slug,
        String description,
        ProjectStatus status,
        UUID ownerUserId,
        Map<String, Object> metadata,
        Instant statusChangedAt,
        Instant createdAt,
        Instant updatedAt) {

    public ProjectView {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
