package com.pesaguard.backend.oauth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import java.util.Set;
import java.util.UUID;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.pesaguard.backend.audit.application.AuditService;
import com.pesaguard.backend.common.exception.BusinessException;
import com.pesaguard.backend.config.OAuthProperties;
import com.pesaguard.backend.oauth.api.TokenRequestBody;
import com.pesaguard.backend.oauth.domain.AuthorizationCode;
import com.pesaguard.backend.oauth.domain.OAuthAccessToken;
import com.pesaguard.backend.oauth.domain.OAuthApplication;
import com.pesaguard.backend.oauth.domain.Pkce;
import com.pesaguard.backend.oauth.domain.RefreshToken;
import com.pesaguard.backend.oauth.infrastructure.AuthorizationCodeRepository;
import com.pesaguard.backend.oauth.infrastructure.ConsentRequestRepository;
import com.pesaguard.backend.oauth.infrastructure.OAuthAccessTokenRepository;
import com.pesaguard.backend.oauth.infrastructure.OAuthApplicationRepository;
import com.pesaguard.backend.oauth.infrastructure.OAuthTokenEventRepository;
import com.pesaguard.backend.oauth.infrastructure.RefreshTokenRepository;
import com.pesaguard.backend.security.credentials.CredentialCryptoService;
import com.pesaguard.backend.security.throttling.RequestThrottleService;

class OAuthTokenServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final String VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
    private static final String REDIRECT_URI = "https://app.example.com/callback";

    private final OAuthApplicationRepository applicationRepository = mock(OAuthApplicationRepository.class);
    private final AuthorizationCodeRepository codeRepository = mock(AuthorizationCodeRepository.class);
    private final RefreshTokenRepository refreshTokenRepository = mock(RefreshTokenRepository.class);
    private final OAuthAccessTokenRepository accessTokenRepository = mock(OAuthAccessTokenRepository.class);
    private final ConsentRequestRepository consentRepository = mock(ConsentRequestRepository.class);
    private final OAuthTokenEventRepository eventRepository = mock(OAuthTokenEventRepository.class);
    private final OAuthClientAuthenticator clientAuthenticator = mock(OAuthClientAuthenticator.class);
    private final RequestThrottleService throttleService = mock(RequestThrottleService.class);

    private final CredentialCryptoService crypto = new CredentialCryptoService(
            new SecretKeySpec("01234567890123456789012345678901".getBytes(StandardCharsets.UTF_8), "HmacSHA256"),
            new SecureRandom());

    private final OAuthProperties properties = new OAuthProperties(
            Duration.ofMinutes(5), Duration.ofMinutes(15), Duration.ofDays(30),
            Duration.ofMinutes(10), Duration.ofMinutes(15), 60);

    private final UUID organizationId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    private OAuthTokenService service;
    private OAuthApplication application;

    @BeforeEach
    void setUp() {
        service = new OAuthTokenService(applicationRepository, codeRepository, refreshTokenRepository,
                accessTokenRepository, consentRepository, eventRepository, clientAuthenticator,
                throttleService, crypto, mock(AuditService.class), properties,
                Clock.fixed(NOW, ZoneOffset.UTC));
        application = OAuthApplication.register(organizationId, "App", "desc", "pgo_client",
                crypto.hmacSha256("secret"), "secret123", Set.of(REDIRECT_URI), Set.of(), Set.of(),
                userId, NOW);
        application.verify("verified", NOW);
        when(applicationRepository.findByClientId("pgo_client")).thenReturn(Optional.of(application));
        when(clientAuthenticator.authenticate("pgo_client", "secret")).thenReturn(Optional.of(application));
        when(refreshTokenRepository.findByApplicationIdAndUserId(any(), any())).thenReturn(List.of());
        when(accessTokenRepository.findByFamilyId(any())).thenReturn(List.of());
        // Mockito returns null from unstubbed saveAndFlush, which would hide the id
        // the rotation needs; echo the argument back like the real repository does.
        when(refreshTokenRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(accessTokenRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(codeRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private AuthorizationCode freshCode() {
        return AuthorizationCode.issue(application.getId(), organizationId, userId,
                crypto.hmacSha256("unused"), REDIRECT_URI, Set.of("payments:read"),
                Pkce.challengeFor(VERIFIER), null, NOW.plusSeconds(300));
    }

    private TokenRequestBody codeExchange(String code, String verifier, String redirectUri) {
        return new TokenRequestBody("authorization_code", "pgo_client", "secret",
                code, redirectUri, verifier, null);
    }

    private RefreshToken usableRefreshToken() {
        return RefreshToken.issue(application.getId(), organizationId, userId, UUID.randomUUID(),
                crypto.hmacSha256("raw-refresh"), Set.of("payments:read"), NOW.plusSeconds(86400));
    }

    private TokenRequestBody refreshGrant(String refreshToken) {
        return new TokenRequestBody("refresh_token", "pgo_client", "secret", null, null, null, refreshToken);
    }

    @Test
    void refreshingPersistsExactlyOneReplacementToken() {
        RefreshToken presented = usableRefreshToken();
        when(refreshTokenRepository.findByTokenHashForUpdate(any())).thenReturn(Optional.of(presented));

        var response = service.token(refreshGrant("raw-refresh"), "203.0.113.5");

        assertThat(response.refreshToken()).isNotBlank().isNotEqualTo("raw-refresh");
        // Exactly one successor is created, plus the write that marks the presented
        // token spent. A duplicate write would leave two live successors.
        ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository, times(2)).saveAndFlush(saved.capture());
        assertThat(saved.getAllValues().stream().map(RefreshToken::getTokenHash).distinct().count())
                .isEqualTo(2);
        verify(accessTokenRepository, times(1)).saveAndFlush(any(OAuthAccessToken.class));
    }

    @Test
    void theRefreshSuccessorKeepsTheOriginalFamily() {
        RefreshToken presented = usableRefreshToken();
        UUID family = presented.getFamilyId();
        when(refreshTokenRepository.findByTokenHashForUpdate(any())).thenReturn(Optional.of(presented));

        service.token(refreshGrant("raw-refresh"), "203.0.113.5");

        ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository, times(2)).saveAndFlush(saved.capture());
        assertThat(saved.getAllValues()).allSatisfy(token ->
                assertThat(token.getFamilyId()).isEqualTo(family));
        assertThat(saved.getAllValues()).anySatisfy(token ->
                assertThat(token.isReplayed()).isTrue());
    }

    @Test
    void reusingASpentRefreshTokenRevokesTheWholeFamily() {
        RefreshToken presented = usableRefreshToken();
        presented.markUsed(NOW.minusSeconds(60), UUID.randomUUID());
        when(refreshTokenRepository.findByTokenHashForUpdate(any())).thenReturn(Optional.of(presented));

        assertThatThrownBy(() -> service.token(refreshGrant("raw-refresh"), "203.0.113.5"))
                .isInstanceOf(BusinessException.class);

        verify(accessTokenRepository).findByFamilyId(presented.getFamilyId());
        verify(refreshTokenRepository).findByApplicationIdAndUserId(application.getId(), userId);
    }

    @Test
    void anExpiredRefreshTokenIsRejectedWithoutIssuing() {
        RefreshToken presented = RefreshToken.issue(application.getId(), organizationId, userId,
                UUID.randomUUID(), crypto.hmacSha256("old"), Set.of("payments:read"), NOW.minusSeconds(1));
        when(refreshTokenRepository.findByTokenHashForUpdate(any())).thenReturn(Optional.of(presented));

        assertThatThrownBy(() -> service.token(refreshGrant("old"), "203.0.113.5"))
                .isInstanceOf(BusinessException.class);
        verify(accessTokenRepository, never()).saveAndFlush(any(OAuthAccessToken.class));
    }

    @Test
    void anAuthorizationCodeIsExchangedExactlyOnce() {
        AuthorizationCode code = freshCode();
        when(codeRepository.findByCodeHashForUpdate(any())).thenReturn(Optional.of(code));

        var first = service.token(codeExchange("raw-code", VERIFIER, REDIRECT_URI), "203.0.113.5");

        assertThat(first.accessToken()).isNotBlank();
        assertThat(first.refreshToken()).isNotBlank();
        assertThat(first.scopes()).containsExactly("payments:read");

        assertThatThrownBy(() -> service.token(codeExchange("raw-code", VERIFIER, REDIRECT_URI), "203.0.113.5"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void aWrongCodeVerifierIsRejectedAndTheCodeSurvives() {
        AuthorizationCode code = freshCode();
        when(codeRepository.findByCodeHashForUpdate(any())).thenReturn(Optional.of(code));

        assertThatThrownBy(() -> service.token(
                codeExchange("raw-code", "z".repeat(43), REDIRECT_URI), "203.0.113.5"))
                .isInstanceOf(BusinessException.class);

        assertThat(code.isConsumed()).isFalse();
    }

    @Test
    void aRedirectUriMismatchIsRejected() {
        AuthorizationCode code = freshCode();
        when(codeRepository.findByCodeHashForUpdate(any())).thenReturn(Optional.of(code));

        assertThatThrownBy(() -> service.token(
                codeExchange("raw-code", VERIFIER, REDIRECT_URI + "/extra"), "203.0.113.5"))
                .isInstanceOf(BusinessException.class);
        assertThat(code.isConsumed()).isFalse();
    }

    @Test
    void clientAuthenticationIsRequired() {
        when(clientAuthenticator.authenticate("pgo_client", "wrong")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.token(
                new TokenRequestBody("authorization_code", "pgo_client", "wrong", "c", REDIRECT_URI, VERIFIER, null),
                "203.0.113.5"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Client authentication failed");
    }

    @Test
    void anExpiredCodeIsRejected() {
        AuthorizationCode code = AuthorizationCode.issue(application.getId(), organizationId, userId,
                crypto.hmacSha256("expired"), REDIRECT_URI, Set.of("payments:read"),
                Pkce.challengeFor(VERIFIER), null, NOW.minusSeconds(10));
        when(codeRepository.findByCodeHashForUpdate(any())).thenReturn(Optional.of(code));

        assertThatThrownBy(() -> service.token(codeExchange("expired", VERIFIER, REDIRECT_URI), "203.0.113.5"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void anUnknownGrantTypeIsRejected() {
        assertThatThrownBy(() -> service.token(
                new TokenRequestBody("password", "pgo_client", "secret", null, null, null, null),
                "203.0.113.5"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void aRevokedApplicationCannotBeUsedForTokenIssuance() {
        application.revoke(NOW);
        when(clientAuthenticator.authenticate("pgo_client", "secret")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.token(codeExchange("c", VERIFIER, REDIRECT_URI), "203.0.113.5"))
                .isInstanceOf(BusinessException.class);
    }
}