package com.pesaguard.backend.oauth.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Replay protection: an authorization code must be redeemable exactly once, and a
 * spent refresh token must be reported as a replay so the family can be revoked.
 */
class OAuthTokenReplayTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final String VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";

private final UUID applicationId = UUID.randomUUID();
    private final UUID organizationId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @Test
    void aConsentRequestKeepsStateForTheCsrfRoundTrip() {
        ConsentRequest consent = ConsentRequest.create(organizationId, applicationId, userId,
                "https://app.example.com/cb", Set.of("payments:read"),
                "state-hash", "opaque-csrf-token", Pkce.challengeFor(VERIFIER), null, NOW.plusSeconds(600));

        assertThat(consent.getStateHash()).isEqualTo("state-hash");
        assertThat(consent.getStatePlaintext()).isEqualTo("opaque-csrf-token");
    }

    @Test
    void aConsentRequestWithoutStateStoresNeitherForm() {
        ConsentRequest consent = ConsentRequest.create(organizationId, applicationId, userId,
                "https://app.example.com/cb", Set.of("payments:read"),
                null, null, Pkce.challengeFor(VERIFIER), null, NOW.plusSeconds(600));

        assertThat(consent.getStateHash()).isNull();
        assertThat(consent.getStatePlaintext()).isNull();
    }

    @Test
    void aConsentRequestCanBeDecidedExactlyOnce() {
        ConsentRequest consent = ConsentRequest.create(organizationId, applicationId, userId,
                "https://app.example.com/cb", Set.of("payments:read"),
                null, null, Pkce.challengeFor(VERIFIER), null, NOW.plusSeconds(600));

        consent.approve(NOW);

        assertThatThrownBy(() -> consent.approve(NOW.plusSeconds(1)))
                .isInstanceOf(IllegalStateException.class);
    }

    private AuthorizationCode code() {
        return AuthorizationCode.issue(applicationId, organizationId, userId, "code-hash",
                "https://app.example.com/cb", Set.of("payments:read"), Pkce.challengeFor(VERIFIER),
                "state-hash", NOW.plusSeconds(300));
    }

    private RefreshToken refresh(UUID familyId) {
        return RefreshToken.issue(applicationId, organizationId, userId, familyId, "token-hash",
                Set.of("payments:read"), NOW.plusSeconds(86400));
    }

    @Test
    void anAuthorizationCodeIsRedeemableExactlyOnce() {
        AuthorizationCode code = code();

        assertThat(code.isConsumed()).isFalse();
        code.consume(NOW.plusSeconds(10));

        assertThat(code.isConsumed()).isTrue();
        assertThatThrownBy(() -> code.consume(NOW.plusSeconds(20)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already been redeemed");
    }

    @Test
    void anExpiredCodeCannotBeRedeemed() {
        AuthorizationCode code = code();

        assertThat(code.isExpired(NOW.plusSeconds(299))).isFalse();
        assertThat(code.isExpired(NOW.plusSeconds(301))).isTrue();
        assertThatThrownBy(() -> code.consume(NOW.plusSeconds(301)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void aCodeCannotBeIssuedWithoutPkce() {
        assertThatThrownBy(() -> AuthorizationCode.issue(applicationId, organizationId, userId, "hash",
                "https://app.example.com/cb", Set.of("payments:read"), "tooshort", null, NOW.plusSeconds(300)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aCodeBindsThePkceChallengeAndScopes() {
        AuthorizationCode code = code();

        assertThat(code.getCodeChallengeMethod()).isEqualTo("S256");
        assertThat(Pkce.verify(VERIFIER, code.getCodeChallenge())).isTrue();
        assertThat(code.getCodeChallenge()).isNotEqualTo(VERIFIER);
        assertThat(code.scopeSet()).containsExactly("payments:read");
        assertThat(code.getRedirectUri()).isEqualTo("https://app.example.com/cb");
        assertThat(code.getStateHash()).isEqualTo("state-hash");
    }

    @Test
    void anUntouchedRefreshTokenIsUsable() {
        RefreshToken token = refresh(UUID.randomUUID());

        assertThat(token.isUsable(NOW)).isTrue();
        assertThat(token.isReplayed()).isFalse();
    }

    @Test
    void aSpentRefreshTokenIsReportedAsReplayed() {
        RefreshToken token = refresh(UUID.randomUUID());
        token.markUsed(NOW.plusSeconds(60), UUID.randomUUID());

        assertThat(token.isReplayed()).isTrue();
        assertThat(token.isUsable(NOW.plusSeconds(60))).isFalse();
        assertThatThrownBy(() -> token.markUsed(NOW.plusSeconds(70), UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already been used");
    }

    @Test
    void aRevokedRefreshTokenIsReportedAsReplayed() {
        RefreshToken token = refresh(UUID.randomUUID());
        token.revoke(NOW.plusSeconds(30), "family_revoked");

        assertThat(token.isReplayed()).isTrue();
        assertThat(token.getRevokeReason()).isEqualTo("family_revoked");
        assertThat(token.isUsable(NOW.plusSeconds(30))).isFalse();
    }

    @Test
    void anExpiredRefreshTokenIsUnusable() {
        RefreshToken token = refresh(UUID.randomUUID());

        assertThat(token.isUsable(NOW.plusSeconds(86401))).isFalse();
    }

    @Test
    void allTokensInAFamilyShareItsIdentifier() {
        UUID familyId = UUID.randomUUID();
        RefreshToken first = refresh(familyId);
        RefreshToken second = refresh(familyId);
        RefreshToken other = refresh(UUID.randomUUID());

        assertThat(first.getFamilyId()).isEqualTo(second.getFamilyId());
        assertThat(first.getFamilyId()).isNotEqualTo(other.getFamilyId());
    }

    @Test
    void aRootRefreshTokenStartsItsOwnFamily() {
        RefreshToken token = refresh(null);

        assertThat(token.getFamilyId()).isNotNull();
    }
}