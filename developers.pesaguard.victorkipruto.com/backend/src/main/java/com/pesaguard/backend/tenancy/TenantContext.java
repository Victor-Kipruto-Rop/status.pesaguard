package com.pesaguard.backend.tenancy;

import java.util.UUID;

/**
 * Immutable request-scoped identity and resource context. The tenant ID is
 * deliberately an alias of the authenticated organization ID; it is never
 * accepted from a request body, query string, or path by itself.
 */
public record TenantContext(
        UUID tenantId,
        UUID organizationId,
        UUID actorId,
        UUID sessionId,
        UUID projectId,
        UUID environmentId,
        String requestId,
        String remoteAddress,
        String userAgent) {

    public TenantContext {
        if (tenantId == null || organizationId == null || !tenantId.equals(organizationId)) {
            throw new IllegalArgumentException("Tenant and organization identifiers must match");
        }
    }
}