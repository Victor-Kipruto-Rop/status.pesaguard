package com.pesaguard.backend.project.api;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record ProjectSettingsView(
        UUID projectId,
        Map<String, Object> settings,
        UUID updatedBy,
        Instant updatedAt) {

    public ProjectSettingsView {
        settings = settings == null ? Map.of() : Map.copyOf(settings);
    }
}