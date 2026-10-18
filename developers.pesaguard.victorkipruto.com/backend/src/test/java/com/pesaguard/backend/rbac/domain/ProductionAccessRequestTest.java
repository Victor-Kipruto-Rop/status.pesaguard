package com.pesaguard.backend.rbac.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class ProductionAccessRequestTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private final UUID organizationId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID environmentId = UUID.randomUUID();
    private final UUID requester = UUID.randomUUID();
    private final UUID reviewer = UUID.randomUUID();

    private ProductionAccessRequest pending() {
        return ProductionAccessRequest.create(organizationId, projectId, environmentId, requester,
                "Investigating a failed settlement batch", "Settlement processor",
                "Example company and operations contact", "Reconcile settled payment batches",
                "transactions:read, reports:read", "Up to 500 requests per minute",
                "Private backend worker with monitored callbacks", "Secrets managed in a vault",
                NOW);
    }

    @Test
    void newRequestIsPending() {
        ProductionAccessRequest request = pending();

        assertThat(request.getStatus()).isEqualTo(ProductionAccessStatus.PENDING);
        assertThat(request.getRequestedBy()).isEqualTo(requester);
        assertThat(request.isPending()).isTrue();
        assertThat(request.getExpiresAt()).isNull();
    }

    @Test
    void reasonIsRequired() {
        assertThatThrownBy(() -> ProductionAccessRequest.create(
                organizationId, projectId, environmentId, requester, "  ", "Settlement processor",
                "Example company details", "Reconcile payment batches", "transactions:read",
                "500 requests per minute", "Private backend worker", "Secrets managed securely", NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void approvalMustBeTimeBoxed() {
        ProductionAccessRequest request = pending();

        assertThatThrownBy(() -> request.approve(reviewer, "ok", NOW, NOW))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> request.approve(reviewer, "ok", NOW.plusSeconds(-10), NOW))
                .isInstanceOf(IllegalStateException.class);
        assertThat(request.getStatus()).isEqualTo(ProductionAccessStatus.PENDING);
    }

    @Test
    void approvalAloneDoesNotGrantUntilActivated() {
        ProductionAccessRequest request = pending();
        Instant expiry = NOW.plusSeconds(3600);

        request.approve(reviewer, "granted for one hour", expiry, NOW);

        assertThat(request.getStatus()).isEqualTo(ProductionAccessStatus.APPROVED);
        assertThat(request.getReviewedBy()).isEqualTo(reviewer);
        assertThat(request.getExpiresAt()).isEqualTo(expiry);
        // Approved is a decision, not a grant: provisioning has not run yet.
        assertThat(request.isActiveGrant(NOW.plusSeconds(1800))).isFalse();

        request.activate(reviewer, NOW.plusSeconds(60));

        assertThat(request.isActiveGrant(NOW.plusSeconds(1800))).isTrue();
        assertThat(request.isActiveGrant(expiry.plusSeconds(1))).isFalse();
    }


    @Test
    void selfReviewIsRefusedByTheDomain() {
        ProductionAccessRequest request = pending();

        assertThatThrownBy(() -> request.approve(requester, "self approved", NOW.plusSeconds(60), NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("self-reviewed");
        assertThatThrownBy(() -> request.reject(requester, "self rejected", NOW))
                .isInstanceOf(IllegalStateException.class);
        assertThat(request.getStatus()).isEqualTo(ProductionAccessStatus.PENDING);
    }

    @Test
    void aReviewedRequestCannotBeReviewedAgain() {
        ProductionAccessRequest request = pending();
        request.approve(reviewer, "ok", NOW.plusSeconds(60), NOW);

        assertThatThrownBy(() -> request.approve(reviewer, "again", NOW.plusSeconds(120), NOW))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> request.reject(reviewer, "again", NOW))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void onlyTheRequesterCanCancel() {
        ProductionAccessRequest request = pending();

        assertThatThrownBy(() -> request.cancel(reviewer, NOW))
                .isInstanceOf(IllegalStateException.class);
        request.cancel(requester, NOW);
        assertThat(request.getStatus()).isEqualTo(ProductionAccessStatus.CANCELLED);
    }

    @Test
    void expiredGrantsLapse() {
        ProductionAccessRequest request = pending();
        Instant expiry = NOW.plusSeconds(60);
        request.approve(reviewer, "ok", expiry, NOW);
        request.activate(reviewer, NOW.plusSeconds(1));

        request.expireIfElapsed(expiry.plusSeconds(1));

        assertThat(request.getStatus()).isEqualTo(ProductionAccessStatus.EXPIRED);
    }


    @Test
    void rejectionRecordsTheReviewer() {
        ProductionAccessRequest request = pending();

        request.reject(reviewer, "insufficient justification", NOW);

        assertThat(request.getStatus()).isEqualTo(ProductionAccessStatus.REJECTED);
        assertThat(request.getReviewNote()).isEqualTo("insufficient justification");
        assertThat(request.getExpiresAt()).isNull();
    }

    @Test
    void archivedCustomRoleGrantsNothing() {
        UUID roleId = UUID.randomUUID();
        CustomRole role = CustomRole.create(organizationId, "Release manager", "Ships releases",
                Set.of(Permission.PROJECT_UPDATE, Permission.ENVIRONMENT_UPDATE), requester);
        assertThat(role.isActive()).isTrue();
        assertThat(role.getPermissions()).containsExactlyInAnyOrder(
                Permission.PROJECT_UPDATE, Permission.ENVIRONMENT_UPDATE);

        role.archive();
        assertThat(role.isActive()).isFalse();
        assertThatThrownBy(() -> role.update("Renamed", null, Set.of(Permission.PROJECT_READ), requester))
                .isInstanceOf(IllegalStateException.class);

        role.restore();
        assertThat(role.isActive()).isTrue();
    }

    @Test
    void aCustomRoleMustGrantAtLeastOnePermission() {
        assertThatThrownBy(() -> CustomRole.create(
                organizationId, "Empty", "no permissions", Set.of(), requester))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CustomRole.create(
                organizationId, "Empty", "null permissions", null, requester))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void roleAssignmentRevocationIsIdempotentAndObservable() {
        RoleAssignment assignment = RoleAssignment.grant(
                organizationId, requester, UUID.randomUUID(), requester, NOW);
        assertThat(assignment.isActive()).isTrue();

        assignment.revoke(reviewer, NOW.plusSeconds(60));
        assertThat(assignment.isActive()).isFalse();
        assertThat(assignment.getRevokedBy()).isEqualTo(reviewer);
        assertThat(assignment.getRevokedAt()).isEqualTo(NOW.plusSeconds(60));

        assignment.revoke(requester, NOW.plusSeconds(120));
        assertThat(assignment.getRevokedBy()).isEqualTo(reviewer);
    }
}
