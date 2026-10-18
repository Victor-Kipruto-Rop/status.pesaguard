package com.pesaguard.backend.security.principals;

import java.util.Set;
import java.util.UUID;

import com.pesaguard.backend.organization.domain.OrganizationStatus;

public record AuthenticatedUser(
        UUID userId,
        UUID organizationId,
        UUID sessionId,
        String email,
        String displayName,
        Set<String> authorities,
        OrganizationStatus organizationStatus,
        boolean serviceAccount,
        Set<String> serviceScopes,
        boolean mfaEnrollmentOnly) {

    public AuthenticatedUser {
        authorities = Set.copyOf(authorities);
        serviceScopes = serviceScopes == null ? Set.of() : Set.copyOf(serviceScopes);
    }

    public AuthenticatedUser(
            UUID userId,
            UUID organizationId,
            UUID sessionId,
            String email,
            String displayName,
            Set<String> authorities) {
        this(userId, organizationId, sessionId, email, displayName, authorities,
                OrganizationStatus.ACTIVE, false, Set.of(), false);
    }

    public AuthenticatedUser(
            UUID userId,
            UUID organizationId,
            UUID sessionId,
            String email,
            String displayName,
            Set<String> authorities,
            OrganizationStatus organizationStatus,
            boolean serviceAccount,
            Set<String> serviceScopes) {
        this(userId, organizationId, sessionId, email, displayName, authorities,
                organizationStatus, serviceAccount, serviceScopes, false);
    }

    public AuthenticatedUser(
            UUID userId,
            UUID organizationId,
            UUID sessionId,
            String email,
            String displayName,
            Set<String> authorities,
            OrganizationStatus organizationStatus) {
        this(userId, organizationId, sessionId, email, displayName, authorities,
                organizationStatus, false, Set.of(), false);
    }

    public boolean organizationActive() {
        return organizationStatus == OrganizationStatus.ACTIVE;
    }
}
