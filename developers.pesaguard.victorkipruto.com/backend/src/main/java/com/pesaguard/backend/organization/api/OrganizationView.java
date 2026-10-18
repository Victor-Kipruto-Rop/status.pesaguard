package com.pesaguard.backend.organization.api;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.pesaguard.backend.organization.domain.OrganizationStatus;
import com.pesaguard.backend.organization.domain.OrganizationType;

public record OrganizationView(
        UUID id,
        String name,
        String slug,
        OrganizationType type,
        OrganizationStatus status,
        UUID ownerUserId,
        Map<String, Object> metadata,
        Instant verifiedAt,
        String verificationReference,
        Instant statusChangedAt,
        Instant deletedAt,
        Instant createdAt,
        Instant updatedAt) {

    public OrganizationView {
        metadata = metadata == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }
}
