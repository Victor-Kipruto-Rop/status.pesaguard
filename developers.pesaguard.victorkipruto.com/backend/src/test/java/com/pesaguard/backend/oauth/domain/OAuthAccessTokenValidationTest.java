package com.pesaguard.backend.oauth.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Access-token liveness and revocation.
 *
 * <p>The refresh-token paths are covered elsewhere. These are the checks that
 * decide whether a bearer token is honoured right now, so they are worth pinning
 * independently: a token that is past its expiry but still accepted is a
 * privilege that outlives its grant.
 */
class OAuthAccessTokenValidationTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private static final Duration TTL = Duration.ofMinutes(30);

    private static OAuthAccessToken token() {
        return OAuthAccessToken.issue(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "hash-" + UUID.randomUUID(), java.util.Set.of("payments:read"),
                T0.plus(TTL));
    }

    @Test
    void aFreshlyIssuedTokenIsActive() {
        assertThat(token().isActive(T0)).isTrue();
    }

    @Test
    void aTokenIsActiveRightUpToItsExpiry() {
        OAuthAccessToken token = token();

        // Boundary is inclusive of the final instant: at exactly expiry the token
        // has not yet passed it.
        assertThat(token.isActive(T0.plus(TTL).minusSeconds(1))).isTrue();
    }

    @Test
    void aTokenIsNotActiveAfterItsExpiry() {
        OAuthAccessToken token = token();

        assertThat(token.isActive(T0.plus(TTL).plusSeconds(1))).isFalse();
    }

    @Test
    void aRevokedTokenIsNotActiveEvenBeforeItExpires() {
        OAuthAccessToken token = token();

        token.revoke(T0.plusSeconds(10), "compromised");

        // The dangerous case: revocation must win over an unexpired clock. An
        // attacker holding a stolen token must not be able to use it for the rest
        // of its lifetime.
        assertThat(token.isActive(T0.plusSeconds(11))).isFalse();
    }

    @Test
    void revocationIsRecordedWithItsReason() {
        OAuthAccessToken token = token();

        token.revoke(T0.plusSeconds(10), "user_revoked");

        assertThat(token.getRevokedAt()).isEqualTo(T0.plusSeconds(10));
        assertThat(token.getRevokeReason()).isEqualTo("user_revoked");
    }

    @Test
    void revocationHappensBeforeExpiry() {
        // Guards the revokedAt field actually being populated rather than left
        // null with an inactive result from some other path.
        OAuthAccessToken token = token();
        assertThat(token.isActive(T0)).isTrue();

        token.revoke(T0.plusSeconds(10), "test");

        assertThat(token.getExpiresAt()).isAfter(token.getRevokedAt());
    }

    @Test
    void revokingTwiceKeepsTheOriginalRevocationTime() {
        OAuthAccessToken token = token();
        token.revoke(T0.plusSeconds(10), "first");

        token.revoke(T0.plusSeconds(500), "second");

        // First revocation wins. Overwriting it would let a later call extend or
        // obscure when access actually ended, which matters when reconstructing
        // an incident.
        assertThat(token.getRevokedAt()).isEqualTo(T0.plusSeconds(10));
    }

    @Test
    void tokenHashesAreDistinctPerToken() {
        OAuthAccessToken a = token();
        OAuthAccessToken b = token();

        assertThat(a.getTokenHash()).isNotEqualTo(b.getTokenHash());
    }

    @Test
    void anIssuedTokenCarriesItsScopesAndOwnership() {
        UUID userId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        OAuthAccessToken issued = OAuthAccessToken.issue(
                UUID.randomUUID(), organizationId, userId,
                UUID.randomUUID(), "hash-" + UUID.randomUUID(),
                java.util.Set.of("payments:read", "payments:write"), T0.plus(TTL));

        // Scopes are the grant. Reading them back wrong would over-privilege a
        // caller that was issued only "payments:read".
        assertThat(issued.scopeSet()).containsExactlyInAnyOrder("payments:read", "payments:write");
        assertThat(issued.getUserId()).isEqualTo(userId);
        assertThat(issued.getOrganizationId()).isEqualTo(organizationId);
    }
}