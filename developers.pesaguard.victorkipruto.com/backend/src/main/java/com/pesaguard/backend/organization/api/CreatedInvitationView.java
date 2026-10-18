package com.pesaguard.backend.organization.api;

import java.time.Instant;
import java.util.UUID;

public record CreatedInvitationView(
        UUID id,
        String email,
        String role,
        Instant expiresAt,
        String deliveryStatus) {
}