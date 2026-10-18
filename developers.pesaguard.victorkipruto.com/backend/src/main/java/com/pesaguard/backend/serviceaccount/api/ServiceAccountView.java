package com.pesaguard.backend.serviceaccount.api;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record ServiceAccountView(
        UUID id,
        String name,
        String description,
        String clientId,
        String clientSecretHint,
        Set<String> scopes,
        String status,
        Instant createdAt) {

    public ServiceAccountView {
        scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
    }
}
