package com.pesaguard.backend.oauth.api;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import com.pesaguard.backend.oauth.domain.ApplicationStatus;

/**
 * An application's public view. The client secret is never present here - it is
 * returned only by the register and rotate-secret responses.
 */
public record ApplicationView(
        UUID id,
        String name,
        String description,
        String clientId,
        String clientSecretHint,
        int clientSecretVersion,
        Set<String> redirectUris,
        Set<String> allowedOrigins,
        Set<String> scopes,
        ApplicationStatus status,
        UUID projectId,
        UUID environmentId,
        Instant verifiedAt,
        String verificationReference,
        Instant statusChangedAt,
        Instant createdAt,
        Instant updatedAt) {

    public ApplicationView {
        redirectUris = redirectUris == null ? Set.of() : Set.copyOf(redirectUris);
        allowedOrigins = allowedOrigins == null ? Set.of() : Set.copyOf(allowedOrigins);
        scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
    }
}