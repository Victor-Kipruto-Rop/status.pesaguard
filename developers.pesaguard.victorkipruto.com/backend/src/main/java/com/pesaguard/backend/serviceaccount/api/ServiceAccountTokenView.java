package com.pesaguard.backend.serviceaccount.api;

import java.util.Set;

public record ServiceAccountTokenView(
        String accessToken,
        String tokenType,
        int expiresIn,
        Set<String> scopes) {

    public ServiceAccountTokenView {
        scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
    }
}
