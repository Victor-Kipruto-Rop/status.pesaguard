package com.pesaguard.backend.security.sessions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.pesaguard.backend.common.exception.UnauthorizedException;
import com.pesaguard.backend.config.ApplicationProperties;
import com.pesaguard.backend.member.domain.UserRefreshToken;
import com.pesaguard.backend.member.domain.RefreshTokenFamily;
import com.pesaguard.backend.member.domain.UserAccount;
import com.pesaguard.backend.member.infrastructure.RefreshTokenFamilyRepository;
import com.pesaguard.backend.member.infrastructure.UserRefreshTokenRepository;
import com.pesaguard.backend.organization.domain.Organization;
import com.pesaguard.backend.organization.domain.OrganizationMembership;
import com.pesaguard.backend.security.credentials.CredentialCryptoService;
import com.pesaguard.backend.securitycenter.application.SecurityEventService;
import com.pesaguard.backend.security.TestProperties;

/**
 * Refresh rotation, reuse detection and the concurrency guard.
 *
 * <p>These are the tests that decide whether a leaked refresh token is survivable.
 * The two that matter most are {@link #aLostClaimIsTreatedAsAReplay} and
 * {@link #aReplayRevokesTheFamilyOutsideTheCallersRollback}: together they cover
 * the two ways the original implementation failed, letting two requests both spend
 * one token and discarding its own revocation when it threw.
 */
class RefreshTokenServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private final UserRefreshTokenRepository tokenRepository = mock(UserRefreshTokenRepository.class);
    private final RefreshTokenFamilyRepository familyRepository = mock(RefreshTokenFamilyRepository.class);
    private final ReplayedRefreshTokenHandler replayHandler = mock(ReplayedRefreshTokenHandler.class);
    private final SecurityEventService securityEventService = mock(SecurityEventService.class);

    private CredentialCryptoService crypto;
    private RefreshTokenService service;

    @BeforeEach
    void setUp() {
        SecretKeySpec credentialKey =
                new SecretKeySpec("01234567890123456789012345678901".getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        crypto = new CredentialCryptoService(credentialKey, new SecureRandom());
        // JpaRepository.save returns the persisted instance; a bare mock returns
        // null, which would make every assertion about the saved token fail for a
        // reason that has nothing to do with the code under test.
        when(familyRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(tokenRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        service = new RefreshTokenService(tokenRepository, familyRepository, crypto, securityEventService,
                replayHandler, properties(), credentialKey, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void rotatesAnUnusedTokenIntoANewOneInTheSameFamily() {
        OrganizationMembership membership = membership();
        RefreshTokenFamily family = family(membership);
        UserRefreshToken token = UserRefreshToken.issue(family.getId(), crypto.sha256("presented"), NOW,
                NOW.plusSeconds(3600), "Chrome", "203.0.113.10");
        givenToken(token);
        givenFamily(family);
        when(tokenRepository.claimForRotation(token.getId(), NOW)).thenReturn(1);

        RefreshTokenService.IssuedRefreshToken issued = service.rotate("presented", "Chrome", "203.0.113.10");

        assertThat(issued.token()).isNotBlank().isNotEqualTo("presented");
        assertThat(issued.familyId()).isEqualTo(family.getId());
        assertThat(issued.expiresAt()).isAfter(NOW);
    }

    @Test
    void anUnknownTokenIsRejected() {
        when(tokenRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.rotate("never-issued", null, null))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("invalid");
        verify(replayHandler, never()).revokeFamilyAndRecord(any(), any());
    }

    @Test
    void aRevokedFamilyRefusesEvenAnIntactToken() {
        // A family killed by sign-out-everywhere may still hold token rows that
        // individually look unused. The family is the unit of revocation.
        OrganizationMembership membership = membership();
        RefreshTokenFamily family = family(membership);
        family.revoke("USER_LOGOUT", NOW.minusSeconds(60));
        givenToken(UserRefreshToken.issue(family.getId(), crypto.sha256("presented"), NOW,
                NOW.plusSeconds(3600), null, null));
        givenFamily(family);

        assertThatThrownBy(() -> service.rotate("presented", null, null))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("invalid");
    }

    @Test
    void anExpiredTokenIsRefusedWithoutBeingMarkedSpent() {
        OrganizationMembership membership = membership();
        RefreshTokenFamily family = family(membership);
        UserRefreshToken expired = UserRefreshToken.issue(family.getId(), crypto.sha256("presented"),
                NOW.minusSeconds(7200), NOW.minusSeconds(3600), null, null);
        givenToken(expired);
        givenFamily(family);

        assertThatThrownBy(() -> service.rotate("presented", null, null))
                .isInstanceOf(UnauthorizedException.class);
        // Spending an expired token would turn a plain expiry into a replay alarm.
        verify(tokenRepository, never()).claimForRotation(any(), any());
    }

    @Test
    void aLostClaimIsTreatedAsAReplay() {
        // Two tabs present the same token. One wins the conditional UPDATE; the
        // other sees zero rows and must be handled exactly like a stolen replay,
        // otherwise it mints a second live token from a single spend.
        OrganizationMembership membership = membership();
        RefreshTokenFamily family = family(membership);
        UserRefreshToken token = UserRefreshToken.issue(family.getId(), crypto.sha256("presented"), NOW,
                NOW.plusSeconds(3600), null, null);
        givenToken(token);
        givenFamily(family);
        when(tokenRepository.claimForRotation(token.getId(), NOW)).thenReturn(0);

        assertThatThrownBy(() -> service.rotate("presented", null, null))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("already been used");
        verify(replayHandler).revokeFamilyAndRecord(family.getId(), NOW);
    }

    @Test
    void aReplayRevokesTheFamilyOutsideTheCallersRollback() {
        // The handler runs in its own transaction precisely so the throw in
        // rotate() cannot roll the revocation back. Asserted by verifying the
        // revocation is delegated to it rather than done inline.
        OrganizationMembership membership = membership();
        RefreshTokenFamily family = family(membership);
        UserRefreshToken token = UserRefreshToken.issue(family.getId(), crypto.sha256("presented"), NOW,
                NOW.plusSeconds(3600), null, null);
        token.markUsed(NOW.minusSeconds(30));
        givenToken(token);
        givenFamily(family);

        assertThatThrownBy(() -> service.rotate("presented", null, null))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("already been used");
        verify(replayHandler).revokeFamilyAndRecord(family.getId(), NOW);
    }

    @Test
    void revokingOneFamilyReportsWhetherItWasLive() {
        OrganizationMembership membership = membership();
        RefreshTokenFamily family = family(membership);
        when(familyRepository.findById(family.getId())).thenReturn(Optional.of(family));
        when(tokenRepository.findActiveByFamilyId(family.getId())).thenReturn(List.of());

        assertThat(service.revokeFamilyById(family.getId(), "USER_LOGOUT")).isTrue();
        assertThat(family.isActive()).isFalse();
        assertThat(family.getRevokedReason()).isEqualTo("USER_LOGOUT");
    }

    @Test
    void revokingAnAlreadyDeadFamilyIsANoOp() {
        OrganizationMembership membership = membership();
        RefreshTokenFamily family = family(membership);
        family.revoke("PASSWORD_RESET", NOW.minusSeconds(60));
        when(familyRepository.findById(family.getId())).thenReturn(Optional.of(family));

        assertThat(service.revokeFamilyById(family.getId(), "USER_LOGOUT")).isFalse();
        // The first recorded reason is retained: "why did this die" is the first
        // question in any incident review.
        assertThat(family.getRevokedReason()).isEqualTo("PASSWORD_RESET");
    }

    @Test
    void revokingEveryFamilyWalksAllOfThem() {
        OrganizationMembership membership = membership();
        RefreshTokenFamily first = family(membership);
        RefreshTokenFamily second = family(membership);
        when(familyRepository.findByUserIdAndRevokedAtIsNull(membership.getUser().getId()))
                .thenReturn(List.of(first, second));
        when(tokenRepository.findActiveByFamilyId(any())).thenReturn(List.of());

        assertThat(service.revokeAllForUser(membership.getUser().getId(), "PASSWORD_RESET")).isEqualTo(2);
        assertThat(first.isActive()).isFalse();
        assertThat(second.isActive()).isFalse();
    }

    @Test
    void theStoredTokenIsNeverTheOnePresented() {
        OrganizationMembership membership = membership();
        RefreshTokenFamily family = family(membership);
        givenFamily(family);

        service.issue(membership, "Chrome", "203.0.113.10");

        ArgumentCaptor<UserRefreshToken> captor = ArgumentCaptor.forClass(UserRefreshToken.class);
        verify(tokenRepository).save(captor.capture());
        // 64 hex characters: a SHA-256 digest, never the token itself.
        assertThat(captor.getValue().getTokenHash()).hasSize(64).matches("[0-9a-f]{64}");
    }

    private void givenToken(UserRefreshToken token) {
        when(tokenRepository.findByTokenHash(crypto.sha256("presented"))).thenReturn(Optional.of(token));
    }

    private void givenFamily(RefreshTokenFamily family) {
        when(familyRepository.findById(family.getId())).thenReturn(Optional.of(family));
    }

    private static RefreshTokenFamily family(OrganizationMembership membership) {
        return RefreshTokenFamily.start(
                membership.getUser().getId(), membership.getOrganization().getId());
    }

    private static OrganizationMembership membership() {
        UserAccount user = UserAccount.create("person@example.com", "Person", "encoded-hash");
        Organization organization = Organization.create(
                "Acme", "acme-" + UUID.randomUUID(), user.getId(), NOW);
        return OrganizationMembership.owner(organization, user);
    }

    private ApplicationProperties properties() {
        return TestProperties.platform();
    }
}