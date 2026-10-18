package com.pesaguard.backend.security.authentication;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Fresh credentials required to remove an active MFA factor. */
public record MfaDisableRequest(
        @NotBlank @Size(max = 200) String currentPassword,
        @Size(max = 32) String code) {
}
