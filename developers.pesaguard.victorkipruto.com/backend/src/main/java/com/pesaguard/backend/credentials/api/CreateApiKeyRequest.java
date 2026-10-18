package com.pesaguard.backend.credentials.api;

import java.util.Set;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateApiKeyRequest(
        @NotBlank @Size(min = 2, max = 120) String name,
        @NotEmpty @Size(max = 20) Set<@Pattern(regexp = "[a-z][a-z0-9-]{1,63}:[a-z][a-z0-9-]{1,63}") String> scopes,
        @NotBlank @Pattern(regexp = "PT(?:[0-9]+H)?(?:[0-9]+M)?") String expiresIn,
        @Size(max = 50) Set<@NotBlank @Size(max = 64) String> ipAllowlist) {

    public CreateApiKeyRequest {
        scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
        ipAllowlist = ipAllowlist == null ? Set.of() : Set.copyOf(ipAllowlist);
    }

    public CreateApiKeyRequest(String name, Set<String> scopes, String expiresIn) {
        this(name, scopes, expiresIn, Set.of());
    }
}
