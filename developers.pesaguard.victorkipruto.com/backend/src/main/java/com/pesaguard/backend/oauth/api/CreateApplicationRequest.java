package com.pesaguard.backend.oauth.api;

import java.util.Set;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateApplicationRequest(
        @NotBlank @Size(min = 2, max = 120) String name,
        @Size(max = 500) String description,
        @NotEmpty @Size(min = 1, max = 10)
        Set<@NotBlank @Size(max = 512) String> redirectUris,
        @Size(max = 10) Set<@NotBlank @Size(max = 255) String> allowedOrigins,
        @Size(max = 20) Set<@Pattern(regexp = "[a-z][a-z0-9-]{1,63}:[a-z][a-z0-9-]{1,63}") String> scopes) {

    public CreateApplicationRequest {
        redirectUris = redirectUris == null ? Set.of() : Set.copyOf(redirectUris);
        allowedOrigins = allowedOrigins == null ? Set.of() : Set.copyOf(allowedOrigins);
        scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
    }
}