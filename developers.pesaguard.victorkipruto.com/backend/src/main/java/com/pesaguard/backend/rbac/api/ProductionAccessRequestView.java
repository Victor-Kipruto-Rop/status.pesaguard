package com.pesaguard.backend.rbac.api;

import java.time.Instant;
import java.util.UUID;

/**
 * A production access request as returned to the portal.
 *
 * <p>{@code status} and {@code live} are both present deliberately. A developer
 * and an operator need to answer two different questions: what is the state of
 * the request, and may this integration call production right now. An approved
 * but not yet activated request is the case where those answers differ.
 */
public record ProductionAccessRequestView(
        UUID id,
        UUID projectId,
        UUID environmentId,
        UUID requestedBy,
        String status,
        String reason,
        String applicationName,
        String organizationDetails,
        String intendedApiUsage,
        String requestedScopes,
        String requestedLimits,
        String integrationInformation,
        String securityInformation,
        boolean live,
        UUID reviewedBy,
        Instant reviewedAt,
        Instant expiresAt,
        UUID activatedBy,
        Instant activatedAt,
        String suspensionReason,
        String revocationReason,
        Instant createdAt) {
}
