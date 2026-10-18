package com.pesaguard.backend.scopes.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * A grant is the auditable record of who was given what, and at which version of
 * the scope's meaning. Revoking it must not erase that it existed.
 */
class ApiScopeAssignmentTest {

    private final UUID organizationId = UUID.randomUUID();
    private final UUID apiKeyId = UUID.randomUUID();
    private final UUID granter = UUID.randomUUID();

    private ApiScopeDefinition paymentsRead() {
        return new ApiScopeDefinition("payments:read", "Read payments.", "payments",
                "payments", "read", 3, false);
    }

    @Test
    void aFreshGrantIsActiveAndRecordsTheScopeVersion() {
        ApiScopeAssignment assignment = ApiScopeAssignment.grant(organizationId, apiKeyId,
                paymentsRead(), granter);

        assertThat(assignment.isActive()).isTrue();
        assertThat(assignment.getScopeName()).isEqualTo("payments:read");
        assertThat(assignment.getScopeVersion()).isEqualTo(3);
        assertThat(assignment.getGrantedBy()).isEqualTo(granter);
        assertThat(assignment.getRevokedAt()).isNull();
    }

    @Test
    void revokingRecordsWhoAndWhy() {
        ApiScopeAssignment assignment = ApiScopeAssignment.grant(organizationId, apiKeyId,
                paymentsRead(), granter);
        UUID revoker = UUID.randomUUID();
        java.time.Instant now = java.time.Instant.parse("2026-03-01T00:00:00Z");

        assignment.revoke(now, revoker, "integration decommissioned");

        assertThat(assignment.isActive()).isFalse();
        assertThat(assignment.getRevokedAt()).isEqualTo(now);
        assertThat(assignment.getRevokedBy()).isEqualTo(revoker);
        assertThat(assignment.getRevocationReason()).isEqualTo("integration decommissioned");
        // The original grant survives revocation: the audit trail must show that
        // this scope was once held, not that it never was.
        assertThat(assignment.getGrantedBy()).isEqualTo(granter);
        assertThat(assignment.getScopeVersion()).isEqualTo(3);
    }

    @Test
    void revokingTwiceIsIdempotent() {
        ApiScopeAssignment assignment = ApiScopeAssignment.grant(organizationId, apiKeyId,
                paymentsRead(), granter);
        java.time.Instant first = java.time.Instant.parse("2026-03-01T00:00:00Z");
        java.time.Instant later = java.time.Instant.parse("2026-03-02T00:00:00Z");
        UUID firstRevoker = UUID.randomUUID();

        assignment.revoke(first, firstRevoker, "first reason");
        assignment.revoke(later, UUID.randomUUID(), "second reason");

        assertThat(assignment.getRevokedAt()).isEqualTo(first);
        assertThat(assignment.getRevokedBy()).isEqualTo(firstRevoker);
        assertThat(assignment.getRevocationReason()).isEqualTo("first reason");
    }
}