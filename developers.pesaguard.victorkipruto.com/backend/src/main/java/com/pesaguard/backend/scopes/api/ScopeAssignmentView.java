package com.pesaguard.backend.scopes.api;

import java.time.Instant;
import java.util.UUID;

/** One recorded scope grant on a credential. */
public record ScopeAssignmentView(
        UUID id,
        UUID apiKeyId,
        String scopeName,
        int scopeVersion,
        UUID grantedBy,
        Instant grantedAt,
        Instant revokedAt,
        UUID revokedBy,
        String revocationReason,
        boolean active) {

    public static ScopeAssignmentView from(
            com.pesaguard.backend.scopes.domain.ApiScopeAssignment assignment) {
        return new ScopeAssignmentView(assignment.getId(), assignment.getApiKeyId(),
                assignment.getScopeName(), assignment.getScopeVersion(), assignment.getGrantedBy(),
                assignment.getGrantedAt(), assignment.getRevokedAt(), assignment.getRevokedBy(),
                assignment.getRevocationReason(), assignment.isActive());
    }
}