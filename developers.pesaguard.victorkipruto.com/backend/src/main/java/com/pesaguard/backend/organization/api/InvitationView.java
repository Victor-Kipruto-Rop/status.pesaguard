package com.pesaguard.backend.organization.api;

import java.time.Instant;
import java.util.UUID;

import com.pesaguard.backend.organization.domain.InvitationStatus;
import com.pesaguard.backend.organization.domain.OrganizationRole;

public record InvitationView(
        UUID id,
        String email,
        OrganizationRole role,
        InvitationStatus status,
        Instant expiresAt,
        UUID invitedBy,
        Instant acceptedAt,
        Instant declinedAt,
        Instant revokedAt,
        Instant cancelledAt,
        Instant createdAt) {
}