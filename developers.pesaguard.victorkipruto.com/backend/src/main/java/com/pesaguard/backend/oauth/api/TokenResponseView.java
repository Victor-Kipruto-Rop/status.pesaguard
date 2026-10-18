package com.pesaguard.backend.oauth.api;

import java.time.Instant;
import java.util.Set;

import com.pesaguard.backend.oauth.domain.OAuthApplication;

/** Token endpoint response. Raw tokens appear here once and are never stored. */
public record TokenResponseView(
        String accessToken,
        String tokenType,
        Integer expiresIn,
        String refreshToken,
        Set<String> scopes) {

    public TokenResponseView {
        scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
    }
}