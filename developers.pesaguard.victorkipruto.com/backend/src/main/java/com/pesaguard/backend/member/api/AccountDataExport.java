package com.pesaguard.backend.member.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.pesaguard.backend.credentials.api.ApiKeyStatus;
import com.pesaguard.backend.member.domain.UserStatus;
import com.pesaguard.backend.organization.domain.MembershipStatus;
import com.pesaguard.backend.organization.domain.OrganizationRole;

public record AccountDataExport(
        Instant exportedAt,
        Profile profile,
        List<Membership> memberships,
        List<CreatedApiKey> createdApiKeys) {

    public AccountDataExport {
        memberships = List.copyOf(memberships);
        createdApiKeys = List.copyOf(createdApiKeys);
    }

    public record Profile(
            UUID id,
            String email,
            String username,
            String displayName,
            UserStatus status,
            Instant createdAt,
            Instant emailVerifiedAt) {
    }

    public record Membership(
            UUID organizationId,
            String organizationName,
            OrganizationRole role,
            MembershipStatus status,
            Instant joinedAt) {
    }

    public record CreatedApiKey(
            UUID id,
            UUID organizationId,
            UUID projectId,
            UUID environmentId,
            String name,
            String prefix,
            List<String> scopes,
            ApiKeyStatus status,
            Instant createdAt,
            Instant expiresAt,
            Instant lastUsedAt) {
    }
}
