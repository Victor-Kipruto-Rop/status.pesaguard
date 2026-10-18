package com.pesaguard.backend.security.authentication;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

public record LoginEmailMfaResendRequest(@NotNull UUID challengeId) {
}
