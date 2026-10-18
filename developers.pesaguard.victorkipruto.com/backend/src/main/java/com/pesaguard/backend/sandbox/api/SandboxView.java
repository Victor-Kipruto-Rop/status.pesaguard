package com.pesaguard.backend.sandbox.api;

import java.time.Instant;
import java.util.UUID;

import com.pesaguard.backend.environment.domain.EnvironmentType;
import com.pesaguard.backend.sandbox.domain.Sandbox;
import com.pesaguard.backend.sandbox.domain.SandboxExecution;
import com.pesaguard.backend.sandbox.domain.SandboxStatus;

/** Catalog view of a sandbox. */
public record SandboxView(
        UUID id,
        UUID projectId,
        UUID environmentId,
        EnvironmentType environmentType,
        String name,
        String description,
        SandboxStatus status,
        Instant expiresAt,
        Instant activatedAt,
        Instant lastResetAt,
        int resetCount,
        Instant createdAt) {

    public static SandboxView from(Sandbox sandbox) {
        return new SandboxView(sandbox.getId(), sandbox.getProjectId(), sandbox.getEnvironmentId(),
                // Always SANDBOX by construction: the sandbox row is only ever created
                // against a sandbox environment, and the isolation guard records it.
                com.pesaguard.backend.environment.domain.EnvironmentType.SANDBOX,
                sandbox.getName(), sandbox.getDescription(), sandbox.getStatus(),
                sandbox.getExpiresAt(), sandbox.getActivatedAt(), sandbox.getLastResetAt(),
                sandbox.getResetCount(), sandbox.getCreatedAt());
    }
}