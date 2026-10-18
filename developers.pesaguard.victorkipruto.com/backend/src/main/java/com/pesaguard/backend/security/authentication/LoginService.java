package com.pesaguard.backend.security.authentication;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pesaguard.backend.audit.application.AuditService;
import com.pesaguard.backend.common.api.RequestContext;
import com.pesaguard.backend.common.exception.BusinessException;
import com.pesaguard.backend.common.exception.EmailVerificationRequiredException;
import com.pesaguard.backend.common.exception.UnauthorizedException;
import com.pesaguard.backend.config.ApplicationProperties;
import com.pesaguard.backend.member.domain.RefreshTokenFamily;
import com.pesaguard.backend.member.application.EmailVerificationService;
import com.pesaguard.backend.organization.application.OrganizationSecuritySettingsService;
import com.pesaguard.backend.organization.domain.OrganizationMembership;
import com.pesaguard.backend.organization.domain.OrganizationSecuritySettings;
import com.pesaguard.backend.organization.infrastructure.OrganizationMembershipRepository;
import com.pesaguard.backend.security.credentials.CredentialCryptoService;
import com.pesaguard.backend.security.principals.AuthenticatedUser;
import com.pesaguard.backend.security.sessions.RefreshTokenService;
import com.pesaguard.backend.security.sessions.SessionService;
import com.pesaguard.backend.security.tokens.RevokedTokenRegistry;
import com.pesaguard.backend.security.throttling.RequestThrottleService;
import com.pesaguard.backend.securitycenter.application.UnfamiliarDeviceDetector;
import com.pesaguard.backend.security.passkeys.PasskeyCredentialRepository;

@Service
public class LoginService {

    private static final Logger log = LoggerFactory.getLogger(LoginService.class);

    private final OrganizationMembershipRepository membershipRepository;
    private final PasswordEncoder passwordEncoder;
    private final String dummyPasswordHash;
    private final RequestThrottleService throttleService;
    private final OrganizationSecuritySettingsService securitySettingsService;
    private final PasswordPolicy passwordPolicy;
    private final SessionService sessionService;
    private final AuditService auditService;
    private final MfaService mfaService;
    private final RefreshTokenService refreshTokenService;
    private final RevokedTokenRegistry revokedTokens;
    private final UnfamiliarDeviceDetector unfamiliarDeviceDetector;
    private final ApplicationProperties properties;
    private final SecretKey credentialKey;
    private final EmailVerificationService emailVerificationService;
    private final PasskeyCredentialRepository passkeyCredentialRepository;

    public LoginService(
            OrganizationMembershipRepository membershipRepository,
            PasswordEncoder passwordEncoder,
            CredentialCryptoService credentialCryptoService,
            RequestThrottleService throttleService,
            OrganizationSecuritySettingsService securitySettingsService,
            PasswordPolicy passwordPolicy,
            SessionService sessionService,
            AuditService auditService,
            MfaService mfaService,
            RefreshTokenService refreshTokenService,
            RevokedTokenRegistry revokedTokens,
            UnfamiliarDeviceDetector unfamiliarDeviceDetector,
            ApplicationProperties properties,
            @Qualifier("credentialHmacKey") SecretKey credentialKey,
            EmailVerificationService emailVerificationService,
            PasskeyCredentialRepository passkeyCredentialRepository) {
        this.membershipRepository = membershipRepository;
        this.passwordEncoder = passwordEncoder;
        this.dummyPasswordHash = passwordEncoder.encode(credentialCryptoService.randomToken(32));
        this.throttleService = throttleService;
        this.securitySettingsService = securitySettingsService;
        this.passwordPolicy = passwordPolicy;
        this.sessionService = sessionService;
        this.auditService = auditService;
        this.mfaService = mfaService;
        this.refreshTokenService = refreshTokenService;
        this.revokedTokens = revokedTokens;
        this.unfamiliarDeviceDetector = unfamiliarDeviceDetector;
        this.properties = properties;
        this.credentialKey = credentialKey;
        this.emailVerificationService = emailVerificationService;
        this.passkeyCredentialRepository = passkeyCredentialRepository;
    }

    @Transactional
    public AuthenticationResponse login(LoginRequest request, String remoteAddress) {
        return login(request, remoteAddress, null);
    }

    /**
     * Signs in, recording where the session came from.
     *
     * @param deviceLabel coarse, sanitised client description; never a raw
     *        {@code User-Agent}, which is attacker-controlled and unbounded
     */
    @Transactional
    public AuthenticationResponse login(LoginRequest request, String remoteAddress,
            String deviceLabel) {
        String identifier = request.email().trim().toLowerCase(java.util.Locale.ROOT);
        String ipHash = subjectHash("ip", remoteAddress == null ? "unknown" : remoteAddress);
        passwordPolicy.validateMaximumLength(request.password());
        throttleService.assertAllowed("ip", ipHash, properties.security().ipLoginFailureLimit());

        boolean emailLogin = identifier.indexOf('@') >= 0;
        List<OrganizationMembership> candidates = emailLogin
                ? membershipRepository.findAllActiveByEmail(identifier)
                : membershipRepository.findAllActiveByUsername(identifier);
        String accountIdentifier = candidates.isEmpty()
                ? identifier
                : candidates.getFirst().getUser().getEmail();
        String accountHash = subjectHash("account", accountIdentifier);
        throttleService.assertAllowed("account", accountHash, properties.security().accountLoginFailureLimit());
        OrganizationMembership membership = request.organizationId() == null
                ? (candidates.isEmpty() ? null : candidates.getFirst())
                : candidates.stream()
                        .filter(candidate -> candidate.getOrganization().getId().equals(request.organizationId()))
                        .findFirst().orElse(null);
        String hash = membership == null ? dummyPasswordHash : membership.getUser().getPasswordHash();
        boolean passwordMatches = passwordEncoder.matches(request.password(), hash);
        if (membership == null || !passwordMatches || !membership.isActive()) {
            throttleService.recordFailure("account", accountHash, properties.security().accountLoginFailureLimit());
            throttleService.recordFailure("ip", ipHash, properties.security().ipLoginFailureLimit());
            throw invalidCredentials();
        }
        if (!membership.getUser().isEmailVerified()) {
            // Separate transaction: the challenge and delivery must survive this
            // request's EMAIL_NOT_VERIFIED response and outer transaction rollback.
            EmailVerificationService.VerificationChallenge challenge =
                    emailVerificationService.issueIfCooldownElapsed(membership.getUser().getEmail());
            throttleService.clear("account", accountHash);
            throw new EmailVerificationRequiredException(
                    challenge == null ? null : challenge.expiresAt(),
                    challenge == null ? null : challenge.resendAvailableAt(),
                    membership.getUser().getEmail());
        }

        membership = resolveMembership(candidates, request.organizationId());

        throttleService.clear("account", accountHash);
        OrganizationSecuritySettings settings =
                securitySettingsService.getOrCreate(membership.getOrganization().getId());
        securitySettingsService.assertPasswordLoginAllowed(settings);
        securitySettingsService.assertIpAllowed(settings, remoteAddress);

        EmailLoginMfaChallenge challenge = emailVerificationService.issueLoginMfa(
                membership.getUser().getId(), membership.getOrganization().getId());
        throw new LoginEmailMfaRequiredException(challenge);
    }

    @Transactional
    public AuthenticationResponse completeEmailMfaLogin(
            UUID challengeId, String code, String deviceLabel, String remoteAddress) {
        EmailVerificationService.LoginMfaIdentity identity =
                emailVerificationService.consumeLoginMfa(challengeId, code);
        OrganizationMembership membership = membershipRepository.findAllActiveByUserId(identity.userId()).stream()
                .filter(OrganizationMembership::isActive)
                .filter(candidate -> candidate.getOrganization().isActive())
                .filter(candidate -> candidate.getUser().isActive() && candidate.getUser().isEmailVerified())
                .filter(candidate -> candidate.getOrganization().getId().equals(identity.organizationId()))
                .findFirst()
                .orElseThrow(() -> new UnauthorizedException(
                        "LOGIN_EMAIL_MFA_INVALID", "That sign-in verification code is invalid or has expired."));
        String ipHash = subjectHash("ip", remoteAddress == null ? "unknown" : remoteAddress);
        String accountHash = subjectHash("account", membership.getUser().getEmail());
        throttleService.assertAllowed("ip", ipHash, properties.security().ipLoginFailureLimit());
        throttleService.assertAllowed("account", accountHash, properties.security().accountLoginFailureLimit());
        OrganizationSecuritySettings settings =
                securitySettingsService.getOrCreate(membership.getOrganization().getId());
        securitySettingsService.assertPasswordLoginAllowed(settings);
        securitySettingsService.assertIpAllowed(settings, remoteAddress);
        throttleService.clear("account", accountHash);
        return issueSession(membership, deviceLabel, remoteAddress);
    }

    /**
     * Completes the initial sign-in using the password and email proof submitted
     * during registration. Subsequent password sign-ins still require email MFA.
     */
    @Transactional
    public AuthenticationResponse completeRegistrationVerification(
            String email, String code, String password, String deviceLabel, String remoteAddress) {
        String identifier = email.trim().toLowerCase(java.util.Locale.ROOT);
        String ipHash = subjectHash("ip", remoteAddress == null ? "unknown" : remoteAddress);
        passwordPolicy.validateMaximumLength(password);
        throttleService.assertAllowed("ip", ipHash, properties.security().ipLoginFailureLimit());

        List<OrganizationMembership> candidates = membershipRepository.findAllActiveByEmail(identifier);
        String accountIdentifier = candidates.isEmpty()
                ? identifier
                : candidates.getFirst().getUser().getEmail();
        String accountHash = subjectHash("account", accountIdentifier);
        throttleService.assertAllowed("account", accountHash, properties.security().accountLoginFailureLimit());
        OrganizationMembership membership = candidates.isEmpty() ? null : candidates.getFirst();
        String storedHash = membership == null ? dummyPasswordHash : membership.getUser().getPasswordHash();
        boolean passwordMatches = passwordEncoder.matches(password, storedHash);
        if (membership == null || !passwordMatches || !membership.isActive()) {
            throttleService.recordFailure("account", accountHash, properties.security().accountLoginFailureLimit());
            throttleService.recordFailure("ip", ipHash, properties.security().ipLoginFailureLimit());
            throw invalidCredentials();
        }
        if (!membership.getUser().isActive() || membership.getUser().isEmailVerified()) {
            throw invalidCredentials();
        }

        emailVerificationService.confirm(identifier, code);
        OrganizationSecuritySettings settings =
                securitySettingsService.getOrCreate(membership.getOrganization().getId());
        securitySettingsService.assertPasswordLoginAllowed(settings);
        securitySettingsService.assertIpAllowed(settings, remoteAddress);
        throttleService.clear("account", accountHash);
        return issueSession(membership, deviceLabel, remoteAddress, "auth.registration.verified");
    }

    @Transactional
    public AuthenticationResponse completeRegistrationVerificationByLink(
            String token, String deviceLabel, String remoteAddress) {
        String ipHash = subjectHash("ip", remoteAddress == null ? "unknown" : remoteAddress);
        throttleService.assertAllowed("ip", ipHash, properties.security().ipLoginFailureLimit());

        var verifiedAccount = emailVerificationService.confirmLegacyLink(token);
        String accountHash = subjectHash("account", verifiedAccount.getEmail());
        throttleService.assertAllowed("account", accountHash, properties.security().accountLoginFailureLimit());

        OrganizationMembership membership = membershipRepository.findAllActiveByEmail(verifiedAccount.getEmail())
                .stream()
                .filter(candidate -> candidate.getUser().getId().equals(verifiedAccount.getId()))
                .findFirst()
                .orElseThrow(this::invalidCredentials);
        OrganizationSecuritySettings settings =
                securitySettingsService.getOrCreate(membership.getOrganization().getId());
        securitySettingsService.assertPasswordLoginAllowed(settings);
        securitySettingsService.assertIpAllowed(settings, remoteAddress);
        throttleService.clear("account", accountHash);
        return issueSession(membership, deviceLabel, remoteAddress, "auth.registration.verified");
    }

    public EmailLoginMfaChallenge resendEmailMfaLogin(UUID challengeId) {
        return emailVerificationService.resendLoginMfa(challengeId);
    }

    /** Issues a fresh session in another workspace after resolving the caller's active membership. */
    @Transactional
    public AuthenticationResponse switchWorkspace(AuthenticatedUser principal, UUID workspaceId,
            String deviceLabel, String remoteAddress) {
        OrganizationMembership membership = membershipRepository
                .findByOrganizationIdAndUserId(workspaceId, principal.userId())
                .filter(OrganizationMembership::isActive)
                .filter(candidate -> candidate.getOrganization().isActive())
                .filter(candidate -> candidate.getUser().isActive())
                .filter(candidate -> candidate.getUser().isEmailVerified())
                .orElseThrow(() -> new UnauthorizedException(
                        "WORKSPACE_ACCESS_DENIED", "You do not have access to that workspace."));

        OrganizationSecuritySettings settings =
                securitySettingsService.getOrCreate(membership.getOrganization().getId());
        securitySettingsService.assertPasswordLoginAllowed(settings);
        securitySettingsService.assertIpAllowed(settings, remoteAddress);

        AuthenticationResponse switched = issueSession(
                membership, deviceLabel, remoteAddress, "auth.workspace.session.issued");
        // Replace the calling device's workspace-bound session immediately;
        // sessions on other devices remain untouched.
        logout(principal.userId(), principal.organizationId(), principal.sessionId());
        auditService.append(membership.getOrganization().getId(), principal.userId(),
                "auth.workspace.switched", "workspace", workspaceId.toString(),
                RequestContext.currentRequestId(),
                Map.of("previousWorkspaceId", principal.organizationId().toString()));
        return switched;
    }

    /**
     * Password gate for the passkey second-factor ceremony. When the user has
     * more than one workspace, the same organization-selection challenge is
     * preserved before a challenge is bound to the selected tenant.
     */
    @Transactional
    public PasskeyLoginBinding verifyPasswordForPasskey(
            String identifierInput, String password, UUID organizationId, String remoteAddress) {
        String identifier = identifierInput.trim().toLowerCase(java.util.Locale.ROOT);
        String ipHash = subjectHash("ip", remoteAddress == null ? "unknown" : remoteAddress);
        passwordPolicy.validateMaximumLength(password);
        throttleService.assertAllowed("ip", ipHash, properties.security().ipLoginFailureLimit());
        boolean emailLogin = identifier.indexOf('@') >= 0;
        List<OrganizationMembership> candidates = emailLogin
                ? membershipRepository.findAllActiveByEmail(identifier)
                : membershipRepository.findAllActiveByUsername(identifier);
        String accountIdentifier = candidates.isEmpty() ? identifier : candidates.getFirst().getUser().getEmail();
        String accountHash = subjectHash("account", accountIdentifier);
        throttleService.assertAllowed("account", accountHash, properties.security().accountLoginFailureLimit());
        OrganizationMembership passwordMembership = organizationId == null
                ? (candidates.isEmpty() ? null : candidates.getFirst())
                : candidates.stream()
                        .filter(candidate -> candidate.getOrganization().getId().equals(organizationId))
                        .findFirst().orElse(null);
        String storedHash = passwordMembership == null
                ? dummyPasswordHash : passwordMembership.getUser().getPasswordHash();
        boolean passwordMatches = passwordEncoder.matches(password, storedHash);
        if (passwordMembership == null || !passwordMembership.isActive() || !passwordMatches) {
            throttleService.recordFailure("account", accountHash, properties.security().accountLoginFailureLimit());
            throttleService.recordFailure("ip", ipHash, properties.security().ipLoginFailureLimit());
            throw invalidCredentials();
        }
        if (!passwordMembership.getUser().isActive()) throw invalidCredentials();
        if (!passwordMembership.getUser().isEmailVerified()) {
            throw new EmailVerificationRequiredException(null, null, passwordMembership.getUser().getEmail());
        }
        OrganizationMembership membership = resolveMembership(candidates, organizationId);
        if (membership == null) throw invalidCredentials();
        if (!passkeyCredentialRepository.existsByUserId(membership.getUser().getId())) {
            throw invalidCredentials();
        }
        OrganizationSecuritySettings settings =
                securitySettingsService.getOrCreate(membership.getOrganization().getId());
        securitySettingsService.assertPasswordLoginAllowed(settings);
        securitySettingsService.assertIpAllowed(settings, remoteAddress);
        throttleService.clear("account", accountHash);
        return new PasskeyLoginBinding(membership.getUser().getId(), membership.getOrganization().getId());
    }

    /** Completes a login only after a passkey assertion has been verified. */
    @Transactional
    public AuthenticationResponse loginWithVerifiedPasskey(
            UUID userId, UUID organizationId, String deviceLabel, String remoteAddress) {
        throw new BusinessException(HttpStatus.GONE, "PASSKEY_LOGIN_DISABLED",
                "Passkeys can only be used to recover account access.");
    }

    @Transactional
    public void recordPasskeyFailure(UUID userId, String remoteAddress) {
        if (userId != null) {
            membershipRepository.findAllByUserId(userId).stream()
                    .findFirst()
                    .ifPresent(membership -> throttleService.recordFailure("account",
                            subjectHash("account", membership.getUser().getEmail()),
                            properties.security().accountLoginFailureLimit()));
        }
        throttleService.recordFailure("ip",
                subjectHash("ip", remoteAddress == null ? "unknown" : remoteAddress),
                properties.security().ipLoginFailureLimit());
    }

    public void assertPasskeyAttemptAllowed(String remoteAddress) {
        throttleService.assertAllowed("ip",
                subjectHash("ip", remoteAddress == null ? "unknown" : remoteAddress),
                properties.security().ipLoginFailureLimit());
    }

    public LoginProtectionStatus loginProtectionStatus(AuthenticatedUser principal) {
        String email = membershipRepository.findByOrganizationIdAndUserId(
                        principal.organizationId(), principal.userId())
                .map(membership -> membership.getUser().getEmail())
                .orElseThrow(() -> new UnauthorizedException(
                        "ACCOUNT_NOT_FOUND", "The account could not be verified."));
        Instant blockedUntil = throttleService.blockedUntil(
                "account", subjectHash("account", email));
        return new LoginProtectionStatus(blockedUntil != null, blockedUntil);
    }

    public void recordMfaEnrollmentFailure(AuthenticatedUser principal, String remoteAddress) {
        membershipRepository.findByOrganizationIdAndUserId(principal.organizationId(), principal.userId())
                .filter(OrganizationMembership::isActive)
                .ifPresent(membership -> recordFailedMfa(membership, remoteAddress));
    }

    @Transactional
    public void revokeCompletedMfaEnrollmentChallenge(AuthenticatedUser principal) {
        if (principal.mfaEnrollmentOnly() && mfaService.isEnabled(principal.userId())) {
            sessionService.revokeMfaEnrollmentChallenge(principal.sessionId());
        }
    }

    public String mfaEnrollmentAccountLabel(AuthenticatedUser principal) {
        return membershipRepository.findByOrganizationIdAndUserId(principal.organizationId(), principal.userId())
                .filter(OrganizationMembership::isActive)
                .map(membership -> membership.getUser().getEmail())
                .orElseThrow(() -> new UnauthorizedException(
                        "ACCOUNT_NOT_FOUND", "The account could not be verified."));
    }

    private AuthenticationResponse issueSession(
            OrganizationMembership membership, String deviceLabel, String remoteAddress) {
        return issueSession(membership, deviceLabel, remoteAddress, "auth.login.succeeded");
    }

    private AuthenticationResponse issueSession(
            OrganizationMembership membership, String deviceLabel, String remoteAddress, String auditAction) {
        membership.getUser().recordWorkspaceAccess(membership.getOrganization().getId());
        OrganizationSecuritySettings settings =
                securitySettingsService.getOrCreate(membership.getOrganization().getId());
        SessionService.IssuedSession session = sessionService.issue(membership,
                Duration.ofMinutes(settings.getSessionTtlMinutes()), settings.getMaxSessions(),
                deviceLabel, remoteAddress);
        // A long-lived refresh token is minted at login so the portal can stay
        // signed in. It is a separate credential from the session: revoking one
        // does not revoke the other.
        RefreshTokenService.IssuedRefreshToken refresh = refreshTokenService.issue(
                membership, deviceLabel, remoteAddress);
        // Pair the two credentials so a later logout can revoke both halves.
        sessionService.linkRefreshFamily(session.sessionId(), refresh.familyId());
        auditService.append(
                membership.getOrganization().getId(), membership.getUser().getId(), auditAction,
                "session", session.sessionId().toString(), RequestContext.currentRequestId(),
                Map.of("refreshTokenExpiresAt", refresh.expiresAt().toString()));
        // Advisory only, and deliberately last: a sign-in from an unfamiliar
        // device is worth recording but must never block or roll back a login
        // that has already been fully authenticated.
        //
        // The guard is here at the call site as well as inside the detector.
        // Relying on the callee's own handling would make this guarantee a
        // property of one class: a decorator, a future rewrite, or a test double
        // would silently reintroduce the ability to fail a login. An
        // authenticated user being denied because an advisory signal could not
        // be written is an availability incident, not a security control.
        try {
            unfamiliarDeviceDetector.evaluate(
                    principalFor(membership, session.sessionId()), deviceLabel, session.sessionId());
        } catch (RuntimeException unavailable) {
            log.warn("Unfamiliar-device check failed for session {}; login unaffected",
                    session.sessionId(), unavailable);
        }
        return response(membership, session, refresh);
    }

    public record PasskeyLoginBinding(UUID userId, UUID organizationId) {
    }

    /**
     * The principal shape the detector needs.
     *
     * <p>Built from the membership that was just authenticated, never from the
     * request, so the organization recorded against the signal is the one the
     * login actually belonged to.
     */
    private AuthenticatedUser principalFor(OrganizationMembership membership, UUID sessionId) {
        return new AuthenticatedUser(
                membership.getUser().getId(),
                membership.getOrganization().getId(),
                sessionId,
                membership.getUser().getEmail(),
                membership.getUser().getDisplayName(),
                Set.of("ROLE_" + membership.getRole().name()),
                membership.getOrganization().getStatus());
    }

    /**
     * Rotates a refresh token and issues a matching access session.
     *
     * <p>The access token is reissued here rather than leaving the client to reuse
     * the expired one. Refreshing that returned only a new refresh token would
     * leave the client unable to call anything until it signed in again with its
     * password, which defeats the point of having a refresh token at all.
     *
     * <p>The membership is re-read rather than trusted from the caller: the family
     * records which organization the login belonged to, and a user who has since
     * been removed from it must not get a fresh session for it.
     *
     * @throws UnauthorizedException if the token is invalid, replayed, or its
     *         membership no longer exists
     */
    @Transactional
    public AuthenticationResponse refresh(String presentedRefreshToken, String deviceLabel, String remoteAddress) {
        RefreshTokenService.IssuedRefreshToken refresh = refreshTokenService.rotate(
                presentedRefreshToken, deviceLabel, remoteAddress);

        RefreshTokenFamily family = refreshTokenService.requireFamily(refresh.familyId());
        OrganizationMembership membership = membershipRepository
                .findByOrganizationIdAndUserId(family.getOrganizationId(), family.getUserId())
                .filter(candidate -> candidate.isActive() && candidate.getUser().isEmailVerified())
                .orElseThrow(() -> new UnauthorizedException(
                        "INVALID_REFRESH_TOKEN", "The refresh token is invalid or has expired."));

        OrganizationSecuritySettings settings = securitySettingsService.getOrCreate(
                membership.getOrganization().getId());
        SessionService.IssuedSession session = sessionService.issue(membership,
                Duration.ofMinutes(settings.getSessionTtlMinutes()), settings.getMaxSessions(),
                deviceLabel, remoteAddress);
        // The new access session stays paired with the family that just rotated,
        // so logging out of this session still kills the refresh chain.
        sessionService.linkRefreshFamily(session.sessionId(), refresh.familyId());

        auditService.append(
                membership.getOrganization().getId(), membership.getUser().getId(), "auth.token.refreshed",
                "session", session.sessionId().toString(), RequestContext.currentRequestId(),
                Map.of("refreshTokenExpiresAt", refresh.expiresAt().toString()));
        return response(membership, session, refresh);
    }

    /**
     * Records a failed second factor.
     *
     * <p>Throttled on the same subjects as a password failure, so an attacker
     * cannot brute-force a six-digit code online. The code space is small enough
     * that an unthrottled challenge is a practical account takeover, and the
     * lockout that already guards passwords must cover this too.
     */
    private void recordFailedMfa(OrganizationMembership membership, String remoteAddress) {
        throttleService.recordFailure("account",
                subjectHash("account", membership.getUser().getEmail()),
                properties.security().accountLoginFailureLimit());
        throttleService.recordFailure("ip",
                subjectHash("ip", remoteAddress == null ? "unknown" : remoteAddress),
                properties.security().ipLoginFailureLimit());
    }

    /**
     * Ends one session and the refresh family that backs it.
     *
     * <p>All three things are revoked together, and the access token goes into the
     * denylist rather than being left to expire. Statelessness costs immediate
     * revocation, so a signed-out session would otherwise keep working for the
     * rest of its five-minute life — including on a shared machine, which is
     * exactly when someone clicks "sign out". One Redis write on an explicit
     * sign-out is a price worth paying; it is not on the request hot path, where
     * the whole point of the signed token is to avoid a round trip.
     *
     * @return true if a live session was ended
     */
    @Transactional
    public void logout(UUID userId, UUID organizationId, UUID sessionId) {
        AuthenticatedUser principal = new AuthenticatedUser(
                userId, organizationId, sessionId, "", "", Set.of());
        // Read the family before revoking, so the pairing is still available
        // afterwards.
        Optional<UUID> familyId = sessionService.refreshFamilyFor(sessionId);
        Optional<Instant> sessionExpiry = sessionService.expiryFor(sessionId);
        if (sessionService.revoke(principal)) {
            familyId.ifPresent(family -> refreshTokenService.revokeFamilyById(family, "USER_LOGOUT"));
            // Denylist the access token so the sign-out is immediate rather than
            // taking effect when the JWT happens to expire.
            sessionExpiry.ifPresent(expiry -> revokedTokens.revoke(sessionId, expiry));
            auditService.append(
                    organizationId, userId, "auth.logout", "session", sessionId.toString(),
                    RequestContext.currentRequestId(), Map.of());
        }
    }

    private OrganizationMembership resolveMembership(
            List<OrganizationMembership> candidates, UUID requestedOrganizationId) {
        if (requestedOrganizationId != null) {
            return candidates.stream()
                    .filter(candidate -> candidate.getOrganization().getId().equals(requestedOrganizationId))
                    .findFirst()
                    .orElse(null);
        }
        if (candidates.isEmpty()) return null;
        UUID preferredWorkspaceId = candidates.getFirst().getUser().getLastAccessedWorkspaceId();
        return candidates.stream()
                .filter(candidate -> candidate.getOrganization().getId().equals(preferredWorkspaceId))
                .findFirst()
                .orElse(candidates.getFirst());
    }

    private AuthenticationResponse response(
            OrganizationMembership membership,
            SessionService.IssuedSession session,
            RefreshTokenService.IssuedRefreshToken refresh) {
        return new AuthenticationResponse(
                session.token(), "Bearer", session.expiresAt(),
                refresh.token(), refresh.expiresAt(),
                new SessionUserResponse(
                        membership.getUser().getId(),
                        membership.getUser().getEmail(),
                        membership.getUser().getDisplayName()),
                new SessionOrganizationResponse(
                        membership.getOrganization().getId(),
                        membership.getOrganization().getName(),
                        membership.getOrganization().getSlug()));
    }

    private String subjectHash(String type, String value) {
        return CredentialCryptoService.hmacSha256(credentialKey, "login-throttle:" + type + ":" + value);
    }

    public record LoginProtectionStatus(boolean locked, Instant lockedUntil) {
    }

    private UnauthorizedException invalidCredentials() {
        return new UnauthorizedException(
                "INVALID_CREDENTIALS", "The email address, username, or password is incorrect.");
    }
}
