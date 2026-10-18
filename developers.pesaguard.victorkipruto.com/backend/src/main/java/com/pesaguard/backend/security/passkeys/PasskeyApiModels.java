package com.pesaguard.backend.security.passkeys;

import java.time.Instant;
import java.util.UUID;

import tools.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class PasskeyApiModels {
    private PasskeyApiModels() {
    }

    public record Options(UUID challengeId, JsonNode publicKey) {
    }

    public record RegistrationCompletion(
            @NotNull UUID challengeId,
            @NotBlank @Size(max = 80) String displayName,
            @NotNull JsonNode credential) {
    }

    public record AssertionStart(UUID organizationId) {
    }

    public record MfaAssertionStart(
            @NotBlank @Size(max = 320) String email,
            @NotBlank @Size(max = 128) String password,
            UUID organizationId) {
    }

    public record AssertionCompletion(@NotNull UUID challengeId, @NotNull JsonNode credential) {
    }

    public record Removal(@NotBlank @Size(max = 200) String currentPassword) {
    }

    public record CredentialView(
            UUID id,
            String displayName,
            Instant createdAt,
            Instant lastUsedAt,
            boolean backupEligible,
            boolean backedUp) {
        static CredentialView from(PasskeyCredential credential) {
            return new CredentialView(credential.getId(), credential.getDisplayName(),
                    credential.getCreatedAt(), credential.getLastUsedAt(),
                    credential.isBackupEligible(), credential.isBackedUp());
        }
    }
}
