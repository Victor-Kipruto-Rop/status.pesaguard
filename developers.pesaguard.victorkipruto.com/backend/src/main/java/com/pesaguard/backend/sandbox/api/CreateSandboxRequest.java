package com.pesaguard.backend.sandbox.api;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Creates a sandbox against an existing SANDBOX environment.
 *
 * <p>{@code environmentId} must already be a sandbox environment; the service
 * refuses anything else. There is no way to create a sandbox against production
 * through this endpoint.
 */
public record CreateSandboxRequest(
        @NotNull UUID environmentId,
        @NotBlank @Size(min = 2, max = 120) String name,
        @Size(max = 500) String description,
        @Pattern(regexp = "P(?:[0-9]+D)?(?:T(?:[0-9]+H)?)?", message = "must be an ISO-8601 duration")
        String ttl) {

    public java.time.Duration ttlDuration() {
        return ttl == null || ttl.isBlank() ? null : java.time.Duration.parse(ttl);
    }
}