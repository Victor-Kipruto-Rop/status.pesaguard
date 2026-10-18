package com.pesaguard.backend.scopes.api;

import java.time.Instant;
import java.util.UUID;

import com.pesaguard.backend.scopes.domain.ApiAccessDecisionRecord;

/**
 * One recorded access decision, including the full per-factor trace.
 *
 * <p>The trace is exposed deliberately. A bare "denied" tells an integrator
 * nothing they can act on; the factor that failed tells them exactly which of
 * their nine assumptions was wrong.
 */
public record AccessDecisionView(
        UUID id,
        UUID apiKeyId,
        UUID projectId,
        UUID environmentId,
        String requestedScope,
        boolean allowed,
        String reasonCode,
        String factorTrace,
        Instant decidedAt) {

    public static AccessDecisionView from(ApiAccessDecisionRecord record) {
        return new AccessDecisionView(record.getId(), record.getApiKeyId(), record.getProjectId(),
                record.getEnvironmentId(), record.getRequestedScope(), record.isAllowed(),
                record.getReasonCode(), record.getFactorTrace(), record.getDecidedAt());
    }
}