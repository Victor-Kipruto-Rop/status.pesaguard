package com.pesaguard.backend.security.authentication;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body for {@code POST /api/v1/auth/mfa/confirm} and the login MFA challenge.
 *
 * <p>Two shapes reach this endpoint: a six-digit TOTP code, and a longer
 * Base64URL recovery code. The character class below is the union of both, and
 * must include {@code _} and {@code -} because Base64URL recovery codes can
 * contain either — omitting them rejects codes this service itself issued.
 */
public record MfaCodeRequest(
        @NotBlank
        @Size(max = 32)
        // Six digits for a TOTP code; recovery codes are longer, so the bound is
        // sized for those and the exact form is checked during verification.
        @Pattern(regexp = "[0-9A-Za-z_-]{6,32}")
        String code) {
}