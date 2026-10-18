package com.pesaguard.backend.security.authentication;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CompleteRegistrationLinkRequest(
        @NotBlank @Size(max = 512) String token) {
}
