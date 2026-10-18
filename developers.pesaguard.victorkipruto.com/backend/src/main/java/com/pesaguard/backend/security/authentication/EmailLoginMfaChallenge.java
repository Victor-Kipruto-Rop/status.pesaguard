package com.pesaguard.backend.security.authentication;

import java.time.Instant;
import java.util.UUID;

public record EmailLoginMfaChallenge(
        UUID challengeId,
        Instant expiresAt,
        Instant resendAvailableAt,
        String maskedEmail) {
}
