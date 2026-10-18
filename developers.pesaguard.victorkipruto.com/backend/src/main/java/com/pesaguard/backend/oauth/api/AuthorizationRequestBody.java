package com.pesaguard.backend.oauth.api;

import java.util.Set;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * An authorization request. PKCE is mandatory: {@code codeChallengeMethod} must
 * be S256 and the challenge must be a valid verifier-shaped value.
 */
public record AuthorizationRequestBody(
        @NotBlank @Size(max = 64) String clientId,
        @NotBlank @Size(max = 512) String redirectUri,
        @Size(max = 128) String scope,
        @Size(max = 512) String state,
        @NotBlank @Size(min = 43, max = 128) String codeChallenge,
        @NotBlank @Pattern(regexp = "S256") String codeChallengeMethod,
        @Size(max = 255) String origin) {

    public Set<String> scopeSet() {
        return scope == null || scope.isBlank() ? Set.of() : Set.of(scope.split("[ ,]+"));
    }
}