package com.pesaguard.backend.oauth.api;

import java.time.Instant;
import java.util.UUID;

/**
 * The redirect instruction returned on approval. The raw code is included exactly
 * once here and is never retrievable afterwards.
 *
 * <p>{@code state} is echoed back so the client can complete its CSRF check, and
 * {@code redirectUrl} is the fully-built target so a consent screen only has to
 * navigate. The server still refuses to build this unless the redirect URI was
 * validated as an exact registered match.
 */
public record AuthorizationApprovalView(
        UUID consentRequestId,
        String code,
        String redirectUri,
        String state,
        String redirectUrl,
        Instant expiresAt) {
}