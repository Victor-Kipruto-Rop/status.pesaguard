package com.pesaguard.backend.security.authentication;

import java.time.Instant;

/**
 * Tokens returned by register and login.
 *
 * <p>{@code accessToken} is the opaque, short-lived session credential validated
 * against {@code auth_sessions} on every request. {@code refreshToken} is the
 * longer-lived rotating credential; it is only ever accepted by
 * {@code POST /api/v1/auth/refresh} and is presented at most once.
 *
 * <p>Both are opaque random values rather than JWTs. A stateless JWT cannot be
 * revoked, and revocation on logout, on device removal, and on account
 * suspension is required here — so a server-side check is kept deliberately.
 *
 * <p>Neither token is stored, logged, or placed in a URL by the server. Both are
 * returned in the body with {@code Cache-Control: no-store}.
 */
public record AuthenticationResponse(
        String accessToken,
        String tokenType,
        Instant expiresAt,
        String refreshToken,
        Instant refreshTokenExpiresAt,
        SessionUserResponse user,
        SessionOrganizationResponse organization) {
}
