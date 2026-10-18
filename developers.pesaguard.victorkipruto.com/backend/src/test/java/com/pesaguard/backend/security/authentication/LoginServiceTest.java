package com.pesaguard.backend.security.authentication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.pesaguard.backend.audit.application.AuditService;
import com.pesaguard.backend.common.exception.BusinessException;
import com.pesaguard.backend.common.exception.EmailVerificationRequiredException;
import com.pesaguard.backend.common.exception.MfaEnrollmentRequiredException;
import com.pesaguard.backend.common.exception.UnauthorizedException;
import com.pesaguard.backend.config.ApplicationProperties;
import com.pesaguard.backend.member.domain.UserAccount;
import com.pesaguard.backend.organization.application.OrganizationSecuritySettingsService;
import com.pesaguard.backend.organization.domain.Organization;
import com.pesaguard.backend.organization.domain.OrganizationMembership;
import com.pesaguard.backend.organization.domain.OrganizationSecuritySettings;
import com.pesaguard.backend.organization.infrastructure.OrganizationMembershipRepository;
import com.pesaguard.backend.security.credentials.CredentialCryptoService;
import com.pesaguard.backend.security.passkeys.PasskeyCredentialRepository;
import com.pesaguard.backend.security.sessions.RefreshTokenService;
import com.pesaguard.backend.security.sessions.SessionService;
import com.pesaguard.backend.security.tokens.RevokedTokenRegistry;
import com.pesaguard.backend.security.throttling.RequestThrottleService;
import com.pesaguard.backend.securitycenter.application.UnfamiliarDeviceDetector;
import com.pesaguard.backend.security.TestProperties;
import com.pesaguard.backend.member.application.EmailVerificationService;

class LoginServiceTest {

    private static final String PASSWORD = "correct horse battery staple";
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private final OrganizationMembershipRepository membershipRepository = mock(OrganizationMembershipRepository.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final RequestThrottleService throttleService = mock(RequestThrottleService.class);
    private final OrganizationSecuritySettingsService securitySettingsService =
            mock(OrganizationSecuritySettingsService.class);
    private final SessionService sessionService = mock(SessionService.class);
    private final MfaService mfaService = mock(MfaService.class);
    private final RefreshTokenService refreshTokenService = mock(RefreshTokenService.class);
    private final AuditService auditService = mock(AuditService.class);
    private final UnfamiliarDeviceDetector unfamiliarDeviceDetector =
            mock(UnfamiliarDeviceDetector.class);
    private final EmailVerificationService emailVerificationService = mock(EmailVerificationService.class);

    private LoginService loginService;

    @BeforeEach
    void setUp() {
        SecretKeySpec credentialKey =
                new SecretKeySpec("01234567890123456789012345678901".getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        CredentialCryptoService cryptoService = new CredentialCryptoService(credentialKey, new SecureRandom());
        ApplicationProperties properties = TestProperties.platform();
        when(passwordEncoder.encode(anyString())).thenReturn("encoded-placeholder");
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);
        when(emailVerificationService.issueLoginMfa(any(), any())).thenAnswer(invocation ->
                new EmailLoginMfaChallenge(UUID.randomUUID(), NOW.plus(Duration.ofMinutes(10)),
                        NOW.plusSeconds(60), "p***@example.com"));
        when(sessionService.issue(any(), any(Duration.class), anyInt(), any(), any()))
                .thenReturn(new SessionService.IssuedSession("token", UUID.randomUUID(), NOW.plusSeconds(600)));
        when(sessionService.issue(any(), any(Duration.class), anyInt()))
                .thenReturn(new SessionService.IssuedSession("token", UUID.randomUUID(), NOW.plusSeconds(600)));
        when(refreshTokenService.issue(any(), any(), any()))
                .thenReturn(new RefreshTokenService.IssuedRefreshToken("refresh", NOW.plusSeconds(86400), UUID.randomUUID()));
        loginService = new LoginService(
                membershipRepository, passwordEncoder, cryptoService, throttleService, securitySettingsService,
                new PasswordPolicy(password -> false), sessionService, auditService, mfaService, refreshTokenService,
                mock(RevokedTokenRegistry.class), unfamiliarDeviceDetector, properties, credentialKey,
                emailVerificationService, mock(PasskeyCredentialRepository.class));
    }

    /**
     * A verified owner membership.
     *
     * <p>Sign-in refuses an unverified address with {@code EMAIL_NOT_VERIFIED}, so a
     * fixture that leaves the account unverified would fail every test in this class
     * for a reason unrelated to what each one is about. Tests that care about the
     * gate itself use {@link #unverifiedMembership()}.
     */
    private OrganizationMembership membership() {
        UserAccount user = verifiedUser();
        Organization organization = Organization.create("Acme", "acme-" + UUID.randomUUID(), user.getId(), NOW);
        return OrganizationMembership.owner(organization, user);
    }

    private UserAccount verifiedUser() {
        UserAccount user = UserAccount.create("person@example.com", "Person", "encoded-hash");
        user.verifyEmail(NOW);
        return user;
    }

    /** Same as {@link #membership()} but with an address that has never been confirmed. */
    private OrganizationMembership unverifiedMembership() {
        UserAccount user = UserAccount.create("person@example.com", "Person", "encoded-hash");
        Organization organization = Organization.create("Acme", "acme-" + UUID.randomUUID(), user.getId(), NOW);
        return OrganizationMembership.owner(organization, user);
    }

    private void givenMemberships(List<OrganizationMembership> memberships) {
        when(membershipRepository.findAllActiveByEmail("person@example.com")).thenReturn(memberships);
        memberships.stream()
                .map(membership -> membership.getUser().getId())
                .distinct()
                .forEach(userId -> when(membershipRepository.findAllActiveByUserId(userId))
                        .thenReturn(memberships.stream()
                                .filter(membership -> membership.getUser().getId().equals(userId))
                                .toList()));
    }

    private void givenUsernameMemberships(List<OrganizationMembership> memberships) {
        when(membershipRepository.findAllActiveByUsername("person")).thenReturn(memberships);
    }

    private void givenSettings(OrganizationMembership membership, int sessionTtlMinutes, int maxSessions) {
        OrganizationSecuritySettings settings = OrganizationSecuritySettings.defaults(
                membership.getOrganization().getId(), NOW);
        settings.update(
                "PASSWORD", sessionTtlMinutes, 120, maxSessions, 12, 72, false,
                "", "LOGIN_FAILURE", null, NOW);
        when(securitySettingsService.getOrCreate(membership.getOrganization().getId())).thenReturn(settings);
    }

    @Test
    void anUnverifiedAddressCannotSignIn() {
        givenMemberships(List.of(unverifiedMembership()));
        Instant expiresAt = NOW.plus(Duration.ofMinutes(10));
        Instant resendAvailableAt = NOW.plus(Duration.ofSeconds(60));
        when(emailVerificationService.issueIfCooldownElapsed("person@example.com"))
                .thenReturn(new EmailVerificationService.VerificationChallenge(
                        NOW, expiresAt, resendAvailableAt));

        assertThatThrownBy(() -> loginService.login(
                new LoginRequest("person@example.com", PASSWORD, null), "203.0.113.10"))
                .isInstanceOfSatisfying(EmailVerificationRequiredException.class, challenge -> {
                    assertThat(challenge).hasMessageContaining("verification code");
                    assertThat(challenge.verificationExpiresAt()).isEqualTo(expiresAt);
                    assertThat(challenge.verificationResendAvailableAt()).isEqualTo(resendAvailableAt);
                });

        // The correct password must not unlock the account: the gate is about
        // proven address ownership, not credentials.
        verify(sessionService, never()).issue(any(), any(Duration.class), anyInt(), any(), any());
        verify(emailVerificationService).issueIfCooldownElapsed("person@example.com");
    }

    @Test
    void requiresEmailVerificationAfterPasswordAuthentication() {
        OrganizationMembership membership = membership();
        givenMemberships(List.of(membership));
        OrganizationSecuritySettings settings = OrganizationSecuritySettings.defaults(
                membership.getOrganization().getId(), NOW);
        when(securitySettingsService.getOrCreate(membership.getOrganization().getId())).thenReturn(settings);
        assertThatThrownBy(() -> loginService.login(
                new LoginRequest("person@example.com", PASSWORD, null), "203.0.113.10"))
                .isInstanceOfSatisfying(LoginEmailMfaRequiredException.class,
                        challenge -> assertThat(challenge.challengeId()).isNotNull());
        verify(emailVerificationService).issueLoginMfa(
                membership.getUser().getId(), membership.getOrganization().getId());
        verify(sessionService, never()).issue(any(), any(Duration.class), anyInt(), any(), any());
    }

    @Test
    void requiresEmailVerificationEvenOnARecentlyActiveRecognisedDevice() {
        OrganizationMembership membership = membership();
        givenMemberships(List.of(membership));
        givenSettings(membership, 30, 3);
        when(unfamiliarDeviceDetector.requiresEmailVerification(
                membership.getUser().getId(), "Chrome on Windows")).thenReturn(false);

        assertThatThrownBy(() -> loginService.login(
                new LoginRequest("person@example.com", PASSWORD, null),
                "203.0.113.10", "Chrome on Windows"))
                .isInstanceOfSatisfying(LoginEmailMfaRequiredException.class,
                        challenge -> assertThat(challenge.challengeId()).isNotNull());
        verify(emailVerificationService).issueLoginMfa(
                membership.getUser().getId(), membership.getOrganization().getId());
        verify(sessionService, never()).issue(any(), any(Duration.class), anyInt(), any(), any());
    }

    @Test
    void singleOrganizationLoginIssuesEmailChallengeForThatOrganization() {
        OrganizationMembership membership = membership();
        givenMemberships(List.of(membership));
        givenSettings(membership, 30, 3);

        assertThatThrownBy(() -> loginService.login(
                new LoginRequest("person@example.com", PASSWORD, null), "203.0.113.10"))
                .isInstanceOf(LoginEmailMfaRequiredException.class);
        verify(emailVerificationService).issueLoginMfa(
                membership.getUser().getId(), membership.getOrganization().getId());
        verify(sessionService, never()).issue(any(), any(Duration.class), anyInt(), any(), any());
    }

    @Test
    void usernameCanBeUsedInsteadOfEmailToSignIn() {
        OrganizationMembership membership = membership();
        givenUsernameMemberships(List.of(membership));
        givenSettings(membership, 30, 3);

        assertThatThrownBy(() -> loginService.login(
                new LoginRequest("Person", PASSWORD, null), "203.0.113.10"))
                .isInstanceOf(LoginEmailMfaRequiredException.class);
        verify(membershipRepository).findAllActiveByUsername("person");
        verify(emailVerificationService).issueLoginMfa(
                membership.getUser().getId(), membership.getOrganization().getId());
    }

    @Test
    void loginAutomaticallyUsesTheLastAccessedWorkspace() {
        UserAccount user = verifiedUser();
        Organization firstOrganization = Organization.create("First", "first-" + UUID.randomUUID(),
                user.getId(), NOW);
        Organization lastOrganization = Organization.create("Last", "last-" + UUID.randomUUID(),
                user.getId(), NOW);
        OrganizationMembership first = OrganizationMembership.owner(firstOrganization, user);
        OrganizationMembership last = OrganizationMembership.owner(lastOrganization, user);
        user.recordWorkspaceAccess(lastOrganization.getId());
        givenMemberships(List.of(first, last));
        givenSettings(last, 480, 10);

        assertThatThrownBy(() -> loginService.login(
                new LoginRequest("person@example.com", PASSWORD, null), "203.0.113.10"))
                .isInstanceOf(LoginEmailMfaRequiredException.class);
        assertThat(user.getLastAccessedWorkspaceId()).isEqualTo(lastOrganization.getId());
        verify(emailVerificationService).issueLoginMfa(user.getId(), lastOrganization.getId());
        verify(sessionService, never()).issue(any(), any(Duration.class), anyInt(), any(), any());
    }

    @Test
    void loginUsesAnActiveWorkspaceWhenNoWorkspaceWasPreviouslyAccessed() {
        UserAccount user = verifiedUser();
        Organization firstOrganization = Organization.create("First", "first-" + UUID.randomUUID(),
                user.getId(), NOW);
        Organization secondOrganization = Organization.create("Second", "second-" + UUID.randomUUID(),
                user.getId(), NOW);
        OrganizationMembership first = OrganizationMembership.owner(firstOrganization, user);
        OrganizationMembership second = OrganizationMembership.owner(secondOrganization, user);
        givenMemberships(List.of(first, second));
        givenSettings(first, 480, 10);

        assertThatThrownBy(() -> loginService.login(
                new LoginRequest("person@example.com", PASSWORD, null), "203.0.113.10"))
                .isInstanceOf(LoginEmailMfaRequiredException.class);
        verify(emailVerificationService).issueLoginMfa(user.getId(), firstOrganization.getId());
        verify(sessionService, never()).issue(any(), any(Duration.class), anyInt(), any(), any());
    }

    @Test
    void aSingleOrganizationLoginIssuesNoWorkspaceSelectionChallenge() {
        OrganizationMembership only = membership();
        givenMemberships(List.of(only));
        givenSettings(only, 480, 10);

        assertThatThrownBy(() -> loginService.login(
                new LoginRequest("person@example.com", PASSWORD, null), "203.0.113.10"))
                .isInstanceOf(LoginEmailMfaRequiredException.class);
    }

    @Test
    void selectedOrganizationMustBeAnActiveMembership() {
        OrganizationMembership first = membership();
        OrganizationMembership second = membership();
        givenMemberships(List.of(first, second));
        givenSettings(second, 480, 10);

        assertThatThrownBy(() -> loginService.login(
                new LoginRequest("person@example.com", PASSWORD, second.getOrganization().getId()), "203.0.113.10"))
                .isInstanceOf(LoginEmailMfaRequiredException.class);
        verify(emailVerificationService).issueLoginMfa(
                second.getUser().getId(), second.getOrganization().getId());
    }

    @Test
    void recoveryFactorsCannotCompleteNormalLogin() {
        OrganizationMembership membership = membership();
        givenMemberships(List.of(membership));
        givenSettings(membership, 480, 10);
        assertThatThrownBy(() -> loginService.login(
                new LoginRequest("person@example.com", PASSWORD, null), "203.0.113.10"))
                .isInstanceOf(LoginEmailMfaRequiredException.class);
        verify(mfaService, never()).verify(any(), anyString());
        verify(emailVerificationService).issueLoginMfa(
                membership.getUser().getId(), membership.getOrganization().getId());
    }

    @Test
    void unknownOrganizationSelectionDoesNotRevealMembership() {
        givenMemberships(List.of(membership()));

        assertThatThrownBy(() -> loginService.login(
                new LoginRequest("person@example.com", PASSWORD, UUID.randomUUID()), "203.0.113.10"))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("incorrect");
        verify(throttleService).recordFailure(eq("account"), anyString(), anyInt());
        verify(sessionService, never()).issue(any(), any(Duration.class), anyInt());
    }

    @Test
    void ipRestrictionsAreEnforcedBeforeSessionIssuance() {
        OrganizationMembership membership = membership();
        givenMemberships(List.of(membership));
        givenSettings(membership, 480, 10);
        org.mockito.Mockito.doThrow(new BusinessException(
                org.springframework.http.HttpStatus.FORBIDDEN, "IP_NOT_ALLOWED", "not permitted"))
                .when(securitySettingsService).assertIpAllowed(any(), any());

        assertThatThrownBy(() -> loginService.login(
                new LoginRequest("person@example.com", PASSWORD, null), "203.0.113.10"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not permitted");
        verify(sessionService, never()).issue(any(), any(Duration.class), anyInt());
    }

    @Test
    void passwordAuthenticationMustBeEnabledForTheOrganization() {
        OrganizationMembership membership = membership();
        givenMemberships(List.of(membership));
        givenSettings(membership, 480, 10);
        org.mockito.Mockito.doThrow(new BusinessException(
                org.springframework.http.HttpStatus.FORBIDDEN, "AUTH_METHOD_DISABLED", "disabled"))
                .when(securitySettingsService).assertPasswordLoginAllowed(any());

        assertThatThrownBy(() -> loginService.login(
                new LoginRequest("person@example.com", PASSWORD, null), "203.0.113.10"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("disabled");
        verify(sessionService, never()).issue(any(), any(Duration.class), anyInt());
    }

    @Test
    void wrongPasswordIsRateLimitedAndRejected() {
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);
        givenMemberships(List.of(membership()));

        assertThatThrownBy(() -> loginService.login(
                new LoginRequest("person@example.com", "wrong password value", null), "203.0.113.10"))
                .isInstanceOf(UnauthorizedException.class);
        verify(throttleService).recordFailure(eq("account"), anyString(), anyInt());
        verify(throttleService).recordFailure(eq("ip"), anyString(), anyInt());
        verify(sessionService, never()).issue(any(), any(Duration.class), anyInt());
    }

    @Test
    void loginSurvivesADetectorThatThrowsAfterEmailVerification() {
        OrganizationMembership membership = membership();
        givenMemberships(List.of(membership));
        givenSettings(membership, 480, 10);
        org.mockito.Mockito.doThrow(new IllegalStateException("signal store exploded"))
                .when(unfamiliarDeviceDetector).evaluate(any(), any(), any());
        UUID challengeId = UUID.randomUUID();
        when(emailVerificationService.consumeLoginMfa(challengeId, "123456"))
                .thenReturn(new EmailVerificationService.LoginMfaIdentity(
                        membership.getUser().getId(), membership.getOrganization().getId()));

        assertThatThrownBy(() -> loginService.login(
                new LoginRequest("person@example.com", PASSWORD, null), "203.0.113.10"))
                .isInstanceOf(LoginEmailMfaRequiredException.class);
        assertThat(loginService.completeEmailMfaLogin(
                challengeId, "123456", "Safari on iOS", "203.0.113.10")).isNotNull();
    }

    @Test
    void aVerifiedEmailLoginIsCheckedForAnUnfamiliarDevice() {
        OrganizationMembership membership = membership();
        givenMemberships(List.of(membership));
        givenSettings(membership, 480, 10);
        UUID challengeId = UUID.randomUUID();
        when(emailVerificationService.consumeLoginMfa(challengeId, "123456"))
                .thenReturn(new EmailVerificationService.LoginMfaIdentity(
                        membership.getUser().getId(), membership.getOrganization().getId()));
        assertThatThrownBy(() -> loginService.login(
                new LoginRequest("person@example.com", PASSWORD, null), "203.0.113.10", "Safari on iOS"))
                .isInstanceOf(LoginEmailMfaRequiredException.class);
        loginService.completeEmailMfaLogin(challengeId, "123456", "Safari on iOS", "203.0.113.10");

        verify(unfamiliarDeviceDetector).evaluate(any(), eq("Safari on iOS"), any());
    }

    @Test
    void verifiedLoginIsAuditedAgainstTheAuthenticatedOrganization() {
        OrganizationMembership membership = membership();
        givenMemberships(List.of(membership));
        givenSettings(membership, 480, 10);
        UUID challengeId = UUID.randomUUID();
        when(emailVerificationService.consumeLoginMfa(challengeId, "123456"))
                .thenReturn(new EmailVerificationService.LoginMfaIdentity(
                        membership.getUser().getId(), membership.getOrganization().getId()));
        assertThatThrownBy(() -> loginService.login(
                new LoginRequest("person@example.com", PASSWORD, null), "203.0.113.10"))
                .isInstanceOf(LoginEmailMfaRequiredException.class);
        loginService.completeEmailMfaLogin(challengeId, "123456", null, "203.0.113.10");

        // Login now also records when the refresh token expires, so the metadata is no
        // longer empty. Asserted rather than loosened to anyMap(): the expiry is
        // what an incident reviewer needs to tell a short-lived session from a
        // long-lived credential, so dropping the check would lose real coverage.
        verify(auditService).append(
                eq(membership.getOrganization().getId()), eq(membership.getUser().getId()),
                eq("auth.login.succeeded"), eq("session"), anyString(), any(),
                eq(Map.of("refreshTokenExpiresAt", NOW.plusSeconds(86400).toString())));
    }
}
