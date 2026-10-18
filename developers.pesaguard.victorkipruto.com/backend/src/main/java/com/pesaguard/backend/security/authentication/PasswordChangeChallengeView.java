package com.pesaguard.backend.security.authentication;

import java.time.Instant;
import java.util.UUID;

public record PasswordChangeChallengeView(
        UUID challengeId,
        String maskedEmail,
        Instant expiresAt) {
}
