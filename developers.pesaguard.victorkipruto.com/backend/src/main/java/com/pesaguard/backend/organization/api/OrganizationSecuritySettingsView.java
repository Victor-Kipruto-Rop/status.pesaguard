package com.pesaguard.backend.organization.api;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record OrganizationSecuritySettingsView(
        UUID organizationId,
        Set<String> allowedAuthMethods,
        int sessionTtlMinutes,
        int idleTimeoutMinutes,
        int maxSessions,
        int credentialMinLength,
        int credentialMaxLength,
        boolean mfaRequired,
        boolean mfaRequiredForAdmins,
        Set<String> ipAllowlist,
        Set<String> securityEventTypes,
        Instant updatedAt) {

    public OrganizationSecuritySettingsView {
        allowedAuthMethods = Set.copyOf(allowedAuthMethods);
        ipAllowlist = Set.copyOf(ipAllowlist);
        securityEventTypes = Set.copyOf(securityEventTypes);
    }
}