package com.pesaguard.backend.organization.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.pesaguard.backend.security.sessions.AuthSessionRepository;

/**
 * Tenant scoping of the session lookup used on every authenticated request.
 *
 * <p>This asserts the shape of the query rather than its behaviour, because the
 * behaviour is only observable against a live database and the invariant is what
 * matters: a suspended membership or a suspended organization must be unable to
 * authenticate even if some code path forgets to revoke sessions.
 *
 * <p>Without this, correctness depends on every caller remembering to call
 * revokeActiveByMembershipId. That is a convention, not a guarantee.
 */
class SessionTenantScopingTest {

    private static String activeLookupQuery() {
        StringBuilder text = new StringBuilder();
        for (java.lang.reflect.Method method : AuthSessionRepository.class.getDeclaredMethods()) {
            if (!method.getName().equals("findActiveByTokenHash")) {
                continue;
            }
            org.springframework.data.jpa.repository.Query query =
                    method.getAnnotation(org.springframework.data.jpa.repository.Query.class);
            if (query != null) {
                text.append(query.value());
            }
        }
        assertThat(text.toString()).as("findActiveByTokenHash must declare an explicit query").isNotEmpty();
        return text.toString();
    }

    @Test
    void theActiveSessionLookupRequiresAnActiveMembership() {
        assertThat(activeLookupQuery())
                .as("a suspended or revoked membership must not authenticate")
                .contains("membership.status = com.pesaguard.backend.organization.domain.MembershipStatus.ACTIVE");
    }

    @Test
    void theActiveSessionLookupRequiresAnActiveOrganization() {
        assertThat(activeLookupQuery())
                .as("a suspended or deleted organization must not authenticate")
                .contains("organization.status = com.pesaguard.backend.organization.domain.OrganizationStatus.ACTIVE");
    }

    @Test
    void theActiveSessionLookupStillRejectsRevokedAndExpiredSessions() {
        String query = activeLookupQuery();

        // The pre-existing guards must survive alongside the new ones.
        assertThat(query).contains("session.revokedAt is null");
        assertThat(query).contains("session.expiresAt > :now");
    }

    @Test
    void theLookupDoesNotAcceptAnUnauthenticatedCallerSuppliedScope() {
        // Everything the query filters on must come from the stored session, never
        // from a parameter the caller controls.
        String query = activeLookupQuery();

        assertThat(query).doesNotContain("principal");
        assertThat(query).doesNotContain("currentOrganization");
    }
}