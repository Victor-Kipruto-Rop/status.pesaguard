package com.pesaguard.backend.oauth.api;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import com.pesaguard.backend.oauth.domain.ConsentStatus;

/** What the resource owner sees before deciding. No code is issued here. */
public record ConsentRequestView(
        UUID id,
        UUID applicationId,
        String clientId,
        String applicationName,
        String redirectUri,
        Set<String> scopes,
        String origin,
        ConsentStatus status,
        Instant expiresAt,
        Instant createdAt) {

    public ConsentRequestView {
        scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
    }
}