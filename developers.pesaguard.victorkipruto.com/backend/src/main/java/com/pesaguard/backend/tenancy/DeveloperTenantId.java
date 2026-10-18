package com.pesaguard.backend.tenancy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Derives the stable tenant partition shared by the developer platform and its
 * pipeline consumer.
 */
public final class DeveloperTenantId {

    private static final String PREFIX = "developer-platform:v1|";

    private DeveloperTenantId() {
    }

    public static String derive(String organizationId, String projectId, String environmentId) {
        String canonicalOrganizationId = canonicalUuid(organizationId, "organizationId");
        String canonicalProjectId = canonicalUuid(projectId, "projectId");
        String canonicalEnvironmentId = canonicalUuid(environmentId, "environmentId");
        String input = PREFIX + canonicalOrganizationId + "|" + canonicalProjectId + "|" + canonicalEnvironmentId;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            return "dp_" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private static String canonicalUuid(String value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must be a canonical UUID.");
        }
        try {
            if (UUID.fromString(value).toString().equals(value)) {
                return value;
            }
        } catch (IllegalArgumentException ignored) {
            // Report all malformed or non-canonical values through one contract.
        }
        throw new IllegalArgumentException(name + " must be a canonical UUID.");
    }
}
