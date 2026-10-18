package com.pesaguard.backend.oauth.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pesaguard.backend.audit.application.AuditService;
import com.pesaguard.backend.common.api.RequestContext;
import com.pesaguard.backend.common.exception.BusinessException;
import com.pesaguard.backend.common.exception.ResourceNotFoundException;
import com.pesaguard.backend.config.OAuthProperties;
import com.pesaguard.backend.oauth.api.AuthorizationApprovalView;
import com.pesaguard.backend.oauth.api.AuthorizationRequestBody;
import com.pesaguard.backend.oauth.api.ConsentRequestView;
import com.pesaguard.backend.oauth.api.IntrospectionResponseView;
import com.pesaguard.backend.oauth.api.TokenRequestBody;
import com.pesaguard.backend.oauth.api.TokenResponseView;
import com.pesaguard.backend.oauth.domain.AuthorizationCode;
import com.pesaguard.backend.oauth.domain.ConsentRequest;
import com.pesaguard.backend.oauth.domain.ConsentStatus;
import com.pesaguard.backend.oauth.domain.OAuthAccessToken;
import com.pesaguard.backend.oauth.domain.OAuthApplication;
import com.pesaguard.backend.oauth.domain.OAuthTokenEvent;
import com.pesaguard.backend.oauth.domain.Pkce;
import com.pesaguard.backend.oauth.domain.RedirectUriPolicy;
import com.pesaguard.backend.oauth.domain.RefreshToken;
import com.pesaguard.backend.oauth.infrastructure.AuthorizationCodeRepository;
import com.pesaguard.backend.oauth.infrastructure.ConsentRequestRepository;
import com.pesaguard.backend.oauth.infrastructure.OAuthAccessTokenRepository;
import com.pesaguard.backend.oauth.infrastructure.OAuthApplicationRepository;
import com.pesaguard.backend.oauth.infrastructure.OAuthTokenEventRepository;
import com.pesaguard.backend.oauth.infrastructure.RefreshTokenRepository;
import com.pesaguard.backend.security.credentials.CredentialCryptoService;
import com.pesaguard.backend.security.principals.AuthenticatedUser;
import com.pesaguard.backend.security.throttling.RequestThrottleService;

/**
 * The OAuth 2.0 authorization-code flow with PKCE.
 *
 * <p>Security properties this service owns:
 * <ul>
 *   <li>No code is issued before the resource owner approves the request.</li>
 *   <li>Codes are single-use, short-lived, and bound to client, redirect URI,
 *       scopes, and PKCE challenge.</li>
 *   <li>Refresh tokens rotate on every use; presenting a spent token revokes the
 *       whole family as a theft response.</li>
 *   <li>Every unauthenticated entry point is rate limited.</li>
 * </ul>
 */
@Service
public class OAuthTokenService {

    private final OAuthApplicationRepository applicationRepository;
    private final AuthorizationCodeRepository codeRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final OAuthAccessTokenRepository accessTokenRepository;
    private final ConsentRequestRepository consentRepository;
    private final OAuthTokenEventRepository eventRepository;
    private final OAuthClientAuthenticator clientAuthenticator;
    private final RequestThrottleService throttleService;
    private final CredentialCryptoService crypto;
    private final AuditService auditService;
    private final OAuthProperties oauthProperties;
    private final Clock clock;

    public OAuthTokenService(
            OAuthApplicationRepository applicationRepository,
            AuthorizationCodeRepository codeRepository,
            RefreshTokenRepository refreshTokenRepository,
            OAuthAccessTokenRepository accessTokenRepository,
            ConsentRequestRepository consentRepository,
            OAuthTokenEventRepository eventRepository,
            OAuthClientAuthenticator clientAuthenticator,
            RequestThrottleService throttleService,
            CredentialCryptoService crypto,
            AuditService auditService,
            OAuthProperties oauthProperties,
            Clock clock) {
        this.applicationRepository = applicationRepository;
        this.codeRepository = codeRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.accessTokenRepository = accessTokenRepository;
        this.consentRepository = consentRepository;
        this.eventRepository = eventRepository;
        this.clientAuthenticator = clientAuthenticator;
        this.throttleService = throttleService;
        this.crypto = crypto;
        this.auditService = auditService;
        this.oauthProperties = oauthProperties;
        this.clock = clock;
    }
/**
     * Starts an authorization request. This records intent only; a code does not
     * exist until the resource owner approves.
     */
    @Transactional
    public ConsentRequestView authorize(AuthenticatedUser principal, AuthorizationRequestBody body,
            String remoteAddress) {
        throttle(remoteAddress);
        OAuthApplication application = applicationRepository.findByClientId(body.clientId().trim())
                .filter(candidate -> candidate.getOrganizationId().equals(principal.organizationId()))
                .filter(candidate -> candidate.getStatus().canAuthorize())
                .orElseThrow(() -> invalidRequest("The client is unknown or cannot authorize."));
        if (!application.isRedirectUriAllowed(body.redirectUri())) {
            throw invalidRequest("The redirect URI is not registered for this client.");
        }
        if (!Pkce.isSupportedMethod(body.codeChallengeMethod())
                || !Pkce.isValidVerifier(body.codeChallenge())) {
            throw invalidRequest("A valid S256 PKCE code challenge is required.");
        }
        for (String scope : body.scopeSet()) {
            if (!application.isScopeAllowed(scope)) {
                throw invalidRequest("The requested scope is not permitted for this client.");
            }
        }
        if (body.origin() != null && !body.origin().isBlank()
                && !RedirectUriPolicy.isAllowedOrigin(body.origin(), application.allowedOriginSet())) {
            throw invalidRequest("The origin is not permitted for this client.");
        }
        ConsentRequest consent = consentRepository.saveAndFlush(ConsentRequest.create(
                principal.organizationId(), application.getId(), principal.userId(),
                body.redirectUri(), body.scopeSet(), hashState(body.state()), body.state(),
                body.codeChallenge(), body.origin(), clock.instant().plus(oauthProperties.consentRequestTtl())));
        audit(principal, "oauth.consent_requested", application.getId(),
                Map.of("consentId", consent.getId().toString()));
        return toConsentView(consent, application);
    }

    @Transactional(readOnly = true)
    public List<ConsentRequestView> pendingConsents(AuthenticatedUser principal) {
        return consentRepository.findByUserIdAndStatusOrderByCreatedAtDesc(principal.userId(), ConsentStatus.PENDING)
                .stream()
                .map(consent -> toConsentView(consent,
                        applicationRepository.findById(consent.getApplicationId()).orElse(null)))
                .toList();
    }

    /** Approving is the only path that mints an authorization code. */
    @Transactional
    public AuthorizationApprovalView approve(AuthenticatedUser principal, UUID consentId) {
        ConsentRequest consent = consentRepository.findByIdAndUserId(consentId, principal.userId())
                .orElseThrow(() -> new ResourceNotFoundException("Authorization request"));
        Instant now = clock.instant();
        consent.approve(now);
        consentRepository.saveAndFlush(consent);

        String rawCode = crypto.randomToken(32);
        AuthorizationCode code = codeRepository.saveAndFlush(AuthorizationCode.issue(
                consent.getApplicationId(), consent.getOrganizationId(), consent.getUserId(),
                crypto.hmacSha256(rawCode), consent.getRedirectUri(), consent.scopeSet(),
                consent.getCodeChallenge(), consent.getStateHash(),
                now.plus(oauthProperties.authorizationCodeTtl())));
        audit(principal, "oauth.consent_approved", consent.getApplicationId(),
                Map.of("consentId", consentId.toString()));
        String state = consent.getStatePlaintext();
        return new AuthorizationApprovalView(consentId, rawCode, consent.getRedirectUri(), state,
                RedirectUriPolicy.appendQuery(consent.getRedirectUri(), "code", rawCode, "state", state),
                code.getExpiresAt());
    }
/**
     * Token endpoint. Both grants require confidential-client authentication, and
     * every failure returns the same generic error so the endpoint cannot be used
     * to probe clients or codes.
     */
    @Transactional
    public TokenResponseView token(TokenRequestBody body, String remoteAddress) {
        throttle(remoteAddress);
        OAuthApplication application = clientAuthenticator.authenticate(body.clientId(), body.clientSecret())
                .orElseThrow(() -> invalidGrant("Client authentication failed."));
        Instant now = clock.instant();
        return switch (body.grantType()) {
            case "authorization_code" -> exchangeCode(application, body, now);
            case "refresh_token" -> rotateRefreshToken(application, body, now);
            default -> throw invalidGrant("Unsupported grant type.");
        };
    }

    private TokenResponseView exchangeCode(OAuthApplication application, TokenRequestBody body, Instant now) {
        if (body.code() == null || body.code().isBlank() || body.redirectUri() == null
                || body.codeVerifier() == null) {
            throw invalidGrant("The authorization code exchange is incomplete.");
        }
        AuthorizationCode code = codeRepository.findByCodeHashForUpdate(crypto.hmacSha256(body.code().trim()))
                .filter(candidate -> candidate.getApplicationId().equals(application.getId()))
                .orElseThrow(() -> invalidGrant("The authorization code is invalid."));

        // Exact match: a code is bound to the redirect URI it was issued for.
        if (!RedirectUriPolicy.matches(code.getRedirectUri(), body.redirectUri())) {
            throw invalidGrant("The redirect URI does not match the authorization request.");
        }
        if (code.isConsumed() || code.isExpired(now)) {
            if (code.isConsumed()) {
                // Replay of an already-redeemed code: assume compromise, kill the grant.
                revokeFamily(code.getApplicationId(), code.getUserId(), null, "authorization_code_replay", now);
            }
            throw invalidGrant("The authorization code is no longer usable.");
        }
        if (!Pkce.verify(body.codeVerifier(), code.getCodeChallenge())) {
            throw invalidGrant("The code verifier does not match the code challenge.");
        }
        code.consume(now);
        codeRepository.saveAndFlush(code);
        return issueTokens(application.getId(), application.getOrganizationId(), code.getUserId(),
                null, code.scopeSet(), now);
    }

    private TokenResponseView rotateRefreshToken(OAuthApplication application, TokenRequestBody body, Instant now) {
        if (body.refreshToken() == null || body.refreshToken().isBlank()) {
            throw invalidGrant("A refresh token is required.");
        }
        RefreshToken presented = refreshTokenRepository.findByTokenHashForUpdate(
                        crypto.hmacSha256(body.refreshToken().trim()))
                .filter(token -> token.getApplicationId().equals(application.getId()))
                .orElseThrow(() -> invalidGrant("The refresh token is invalid."));

        if (presented.isReplayed()) {
            // A spent or revoked token was presented: assume theft and kill the grant.
            revokeFamily(presented.getApplicationId(), presented.getUserId(), presented.getFamilyId(),
                    "refresh_token_reuse_detected", now);
            throw invalidGrant("The refresh token is invalid.");
        }
        if (!presented.isUsable(now)) {
            throw invalidGrant("The refresh token is expired.");
        }
        String rawRefresh = crypto.randomToken(32);
        RefreshToken replacement = refreshTokenRepository.saveAndFlush(RefreshToken.issue(
                presented.getApplicationId(), presented.getOrganizationId(), presented.getUserId(),
                presented.getFamilyId(), crypto.hmacSha256(rawRefresh), presented.scopeSet(),
                now.plus(oauthProperties.refreshTokenTtl())));
        presented.markUsed(now, replacement.getId());
        refreshTokenRepository.saveAndFlush(presented);
        recordEvent(presented.getApplicationId(), presented.getFamilyId(), "refresh_token.rotated", now);
        return issueTokens(presented.getApplicationId(), presented.getOrganizationId(), presented.getUserId(),
                presented.getFamilyId(), presented.scopeSet(), now, rawRefresh, replacement);
    }

    private TokenResponseView issueTokens(UUID applicationId, UUID organizationId, UUID userId,
            UUID familyId, java.util.Set<String> scopes, Instant now) {
        return issueTokens(applicationId, organizationId, userId, familyId, scopes, now, null, null);
    }

    /**
     * Persists exactly one access token and at most one refresh token. A refresh
     * token is only written here when {@code existingRefresh} is null, so a caller
     * that already persisted a successor cannot cause a second one to be written
     * with the same value.
     */
    private TokenResponseView issueTokens(UUID applicationId, UUID organizationId, UUID userId,
            UUID familyId, java.util.Set<String> scopes, Instant now, String rawRefreshToken,
            RefreshToken existingRefresh) {
        UUID resolvedFamily = familyId == null ? UUID.randomUUID() : familyId;
        String rawAccess = crypto.randomToken(32);
        accessTokenRepository.saveAndFlush(OAuthAccessToken.issue(
                applicationId, organizationId, userId, resolvedFamily, crypto.hmacSha256(rawAccess),
                scopes, now.plus(oauthProperties.accessTokenTtl())));
        String returnedRefresh = rawRefreshToken;
        if (existingRefresh == null) {
            returnedRefresh = crypto.randomToken(32);
            refreshTokenRepository.saveAndFlush(RefreshToken.issue(
                    applicationId, organizationId, userId, resolvedFamily,
                    crypto.hmacSha256(returnedRefresh), scopes, now.plus(oauthProperties.refreshTokenTtl())));
        }
        recordEvent(applicationId, resolvedFamily, "token.issued", now);
        return new TokenResponseView(rawAccess, "Bearer",
                Math.toIntExact(oauthProperties.accessTokenTtl().toSeconds()), returnedRefresh, scopes);
    }

    /** Revokes every live grant an application holds, across all users. */
    @Transactional
    public void revokeAllGrants(UUID applicationId, String reason) {
        Instant now = clock.instant();
        for (RefreshToken token : refreshTokenRepository.findByApplicationId(applicationId)) {
            if (token.isUsable(now)) {
                token.revoke(now, reason);
                refreshTokenRepository.save(token);
            }
        }
        for (OAuthAccessToken token : accessTokenRepository.findByApplicationId(applicationId)) {
            if (token.isActive(now)) {
                token.revoke(now, reason);
                accessTokenRepository.save(token);
            }
        }
    }

    /** Revokes every live refresh token (and the family's access tokens) for a grant. */
    private void revokeFamily(UUID applicationId, UUID userId, UUID familyId, String reason, Instant now) {
        for (RefreshToken token : refreshTokenRepository.findByApplicationIdAndUserId(applicationId, userId)) {
            if (familyId != null && !familyId.equals(token.getFamilyId())) {
                continue;
            }
            if (token.isUsable(now)) {
                token.revoke(now, reason);
                refreshTokenRepository.save(token);
            }
        }
        if (familyId != null) {
            for (OAuthAccessToken token : accessTokenRepository.findByFamilyId(familyId)) {
                if (token.isActive(now)) {
                    token.revoke(now, reason);
                    accessTokenRepository.save(token);
                }
            }
        }
        recordEvent(applicationId, familyId, "grant.revoked:" + reason, now);
    }

    /**
     * Revocation endpoint (RFC 7009). Unknown tokens are reported as success so
     * the endpoint cannot be used to test whether a token exists.
     */
    @Transactional
    public void revoke(String clientId, String clientSecret, String token) {
        OAuthApplication application = clientAuthenticator.authenticate(clientId, clientSecret)
                .orElseThrow(() -> invalidGrant("Client authentication failed."));
        if (token == null || token.isBlank()) {
            return;
        }
        Instant now = clock.instant();
        String hash = crypto.hmacSha256(token.trim());
        refreshTokenRepository.findByTokenHash(hash)
                .filter(candidate -> candidate.getApplicationId().equals(application.getId()))
                .ifPresent(candidate -> {
                    revokeFamily(candidate.getApplicationId(), candidate.getUserId(), candidate.getFamilyId(),
                            "client_revoked", now);
                    auditSystem(application.getOrganizationId(), "oauth.grant_revoked",
                            Map.of("applicationId", application.getId().toString()));
                });
        accessTokenRepository.findByTokenHash(hash)
                .filter(candidate -> candidate.getApplicationId().equals(application.getId()))
                .ifPresent(candidate -> {
                    candidate.revoke(now, "client_revoked");
                    accessTokenRepository.saveAndFlush(candidate);
                });
    }

    /** Introspection (RFC 7662). Requires client authentication. */
    @Transactional(readOnly = true)
    public IntrospectionResponseView introspect(String clientId, String clientSecret, String token) {
        OAuthApplication application = clientAuthenticator.authenticate(clientId, clientSecret)
                .orElseThrow(() -> invalidGrant("Client authentication failed."));
        if (token == null || token.isBlank()) {
            return IntrospectionResponseView.inactive();
        }
        Instant now = clock.instant();
        String hash = crypto.hmacSha256(token.trim());
        return accessTokenRepository.findByTokenHash(hash)
                .filter(candidate -> candidate.getApplicationId().equals(application.getId()))
                .filter(candidate -> candidate.isActive(now))
                .map(candidate -> new IntrospectionResponseView(true, candidate.getApplicationId(),
                        candidate.getUserId(), candidate.scopeSet(), candidate.getExpiresAt(),
                        candidate.getCreatedAt()))
                .orElseGet(IntrospectionResponseView::inactive);
    }

    /** Bounds an unauthenticated endpoint by source address. */
    public void enforceClientThrottle(String remoteAddress) {
        throttle(remoteAddress);
    }

    @Transactional
    public void deny(AuthenticatedUser principal, UUID consentId) {
        ConsentRequest consent = consentRepository.findByIdAndUserId(consentId, principal.userId())
                .orElseThrow(() -> new ResourceNotFoundException("Authorization request"));
        consent.deny(clock.instant());
        consentRepository.saveAndFlush(consent);
        audit(principal, "oauth.consent_denied", consent.getApplicationId(),
                Map.of("consentId", consentId.toString()));
    }

    private void throttle(String remoteAddress) {
        String subject = crypto.hmacSha256("oauth-throttle:" + (remoteAddress == null ? "unknown" : remoteAddress));
        int limit = oauthProperties.tokenEndpointAttemptLimit();
        throttleService.assertAllowed("oauth_token", subject, limit);
        throttleService.recordAttempt("oauth_token", subject, limit, oauthProperties.throttleWindow());
    }

    private String hashState(String state) {
        return state == null || state.isBlank() ? null : crypto.hmacSha256(state.trim());
    }

    private void recordEvent(UUID applicationId, UUID familyId, String action, Instant now) {
        eventRepository.save(OAuthTokenEvent.record(
                applicationRepository.findById(applicationId)
                        .map(OAuthApplication::getOrganizationId)
                        .orElse(null),
                applicationId, familyId, action, null, null, now));
    }

    private void audit(AuthenticatedUser principal, String action, UUID applicationId, Map<String, ?> metadata) {
        auditService.append(principal.organizationId(), principal.userId(), action, "oauth_application",
                applicationId.toString(), RequestContext.currentRequestId(), metadata);
    }

    private void auditSystem(UUID organizationId, String action, Map<String, ?> metadata) {
        auditService.append(organizationId, null, action, "oauth_grant", "token", RequestContext.currentRequestId(),
                metadata);
    }

    private ConsentRequestView toConsentView(ConsentRequest consent, OAuthApplication application) {
        return new ConsentRequestView(consent.getId(), consent.getApplicationId(),
                application == null ? null : application.getClientId(),
                application == null ? null : application.getName(),
                consent.getRedirectUri(), consent.scopeSet(), consent.getOrigin(),
                consent.getStatus(), consent.getExpiresAt(), consent.getCreatedAt());
    }

    private BusinessException invalidRequest(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_AUTHORIZATION_REQUEST", message);
    }

    private BusinessException invalidGrant(String message) {
        return new BusinessException(HttpStatus.UNAUTHORIZED, "INVALID_GRANT", message);
    }
}
