package com.pesaguard.backend.serviceaccount.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ServiceAccountTokenRequest(
        @NotBlank String grantType,
        @NotBlank @Size(max = 64) String clientId,
        @NotBlank @Size(max = 512) String clientSecret) {
}
