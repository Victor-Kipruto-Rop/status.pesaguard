package com.pesaguard.backend.credentials.api;

import java.util.Set;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateApiKeyRestrictionsRequest(
        @Size(max = 50) Set<@NotBlank @Size(max = 64) String> ipAllowlist) {

    public UpdateApiKeyRestrictionsRequest {
        ipAllowlist = ipAllowlist == null ? Set.of() : Set.copyOf(ipAllowlist);
    }
}
