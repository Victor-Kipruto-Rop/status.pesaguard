package com.pesaguard.backend.environment.api;

import java.time.Instant;
import java.util.UUID;

import com.pesaguard.backend.environment.domain.EnvironmentCredential;
import com.pesaguard.backend.environment.domain.EnvironmentCredentialStatus;
import com.pesaguard.backend.environment.domain.EnvironmentCredentialType;

/** Secret-free environment credential metadata. */
public record EnvironmentCredentialView(
        UUID id,
        String name,
        EnvironmentCredentialType type,
        EnvironmentCredentialStatus status,
        int version,
        Instant rotatedAt,
        Instant createdAt,
        Instant updatedAt) {

    public static EnvironmentCredentialView from(EnvironmentCredential credential) {
        return new EnvironmentCredentialView(credential.getId(), credential.getName(),
                credential.getCredentialType(), credential.getStatus(), credential.getVersion(),
                credential.getRotatedAt(), credential.getCreatedAt(), credential.getUpdatedAt());
    }
}
