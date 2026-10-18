package com.pesaguard.backend.organization.api;

import java.time.Instant;
import java.util.UUID;

import com.pesaguard.backend.organization.domain.MembershipStatus;
import com.pesaguard.backend.organization.domain.OrganizationRole;

public record MembershipHistoryView(
        UUID id,
        UUID membershipId,
        UUID userId,
        MembershipStatus fromStatus,
        MembershipStatus toStatus,
        OrganizationRole fromRole,
        OrganizationRole toRole,
        UUID actorUserId,
        String reason,
        Instant createdAt) {
}