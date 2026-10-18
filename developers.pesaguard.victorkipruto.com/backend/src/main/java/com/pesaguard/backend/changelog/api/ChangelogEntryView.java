package com.pesaguard.backend.changelog.api;

import java.time.Instant;
import java.util.UUID;

public record ChangelogEntryView(
        UUID id,
        String version,
        String title,
        String body,
        String category,
        String status,
        Instant publishedAt,
        Instant createdAt) {
}
