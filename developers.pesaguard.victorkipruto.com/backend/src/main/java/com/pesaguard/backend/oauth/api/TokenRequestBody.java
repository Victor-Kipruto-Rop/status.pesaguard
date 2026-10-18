package com.pesaguard.backend.oauth.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Token endpoint request. {@code grantType} selects the flow; the remaining
 * fields are validated per grant inside the service, because "authorization_code"
 * needs redirectUri/codeVerifier and "refresh_token" does not.
 */
public record TokenRequestBody(
        @NotBlank String grantType,
        @NotBlank @Size(max = 64) String clientId,
        @NotBlank @Size(max = 512) String clientSecret,
        @Size(max = 512) String code,
        @Size(max = 512) String redirectUri,
        @Size(max = 128) String codeVerifier,
        @Size(max = 512) String refreshToken) {
}