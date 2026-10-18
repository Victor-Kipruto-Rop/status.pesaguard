package com.pesaguard.backend.organization.api;

import java.time.Instant;

import com.pesaguard.backend.organization.domain.InvitationStatus;

public record InvitationPreviewView(
        InvitationStatus status,
        String organizationName,
        String role,
        String inviterName,
        String invitedEmailHint,
        Instant expiresAt) {
}
