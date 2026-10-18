package com.pesaguard.backend.security.authentication;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record LoginEmailMfaVerifyRequest(
        @NotNull UUID challengeId,
        @NotNull @Pattern(regexp = "\\d{6}") String code) {
}
