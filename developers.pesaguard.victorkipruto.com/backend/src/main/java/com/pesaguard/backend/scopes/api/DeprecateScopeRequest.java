package com.pesaguard.backend.scopes.api;

import jakarta.validation.constraints.Size;

/** Marks a scope deprecated in favour of a replacement, with a stated reason. */
public record DeprecateScopeRequest(
        @Size(max = 64) String replacedBy,
        @Size(max = 500) String reason) {
}