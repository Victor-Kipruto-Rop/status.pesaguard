package com.pesaguard.backend.scopes.api;

import java.util.Set;
import java.util.UUID;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Grants a set of scopes to a credential. */
public record AssignScopesRequest(
        @NotEmpty @Size(max = 20) Set<@Pattern(regexp = "[a-z][a-z0-9-]{1,47}:[a-z][a-z0-9-]{1,23}") String> scopes) {

    public AssignScopesRequest {
        scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
    }
}