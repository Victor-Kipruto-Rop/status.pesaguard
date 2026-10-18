package com.pesaguard.backend.serviceaccount.api;

import java.util.Set;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

public record ServiceAccountRequest(
        @NotBlank @Size(min = 2, max = 120) String name,
        @Size(max = 500) String description,
        @NotEmpty @Size(max = 40) Set<@NotBlank @Size(max = 80) String> scopes) {

    public ServiceAccountRequest {
        scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
    }
}
