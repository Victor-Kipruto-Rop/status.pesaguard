package com.pesaguard.backend.oauth.application;

import java.time.Clock;
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
import com.pesaguard.backend.oauth.api.ApplicationView;
import com.pesaguard.backend.oauth.api.CreatedApplicationView;
import com.pesaguard.backend.oauth.api.CreateApplicationRequest;
import com.pesaguard.backend.oauth.api.VerifyApplicationRequest;
import com.pesaguard.backend.oauth.domain.OAuthApplication;
import com.pesaguard.backend.oauth.domain.RedirectUriPolicy;
import com.pesaguard.backend.oauth.infrastructure.OAuthAccessTokenRepository;
import com.pesaguard.backend.oauth.infrastructure.OAuthApplicationRepository;
import com.pesaguard.backend.oauth.infrastructure.RefreshTokenRepository;
import com.pesaguard.backend.rbac.application.AuthorizationService;
import com.pesaguard.backend.rbac.domain.Permission;
import com.pesaguard.backend.security.credentials.CredentialCryptoService;
import com.pesaguard.backend.security.principals.AuthenticatedUser;

/**
 * Registration and lifecycle management for OAuth applications.
 *
 * <p>Redirect URIs are validated at write time, not only at authorization time,
 * so an invalid registration fails up front instead of breaking a live
 * integration later.
 */
@Service
public class OAuthApplicationService {

    private final OAuthApplicationRepository applicationRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final OAuthAccessTokenRepository accessTokenRepository;
    private final AuthorizationService authorizationService;
    private final CredentialCryptoService crypto;
    private final AuditService auditService;
    private final Clock clock;

    public OAuthApplicationService(
            OAuthApplicationRepository applicationRepository,
            RefreshTokenRepository refreshTokenRepository,
            OAuthAccessTokenRepository accessTokenRepository,
            AuthorizationService authorizationService,
            CredentialCryptoService crypto,
            AuditService auditService,
            Clock clock) {
        this.applicationRepository = applicationRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.accessTokenRepository = accessTokenRepository;
        this.authorizationService = authorizationService;
        this.crypto = crypto;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional
    public CreatedApplicationView register(AuthenticatedUser principal, CreateApplicationRequest request) {
        authorizationService.requirePermission(principal, Permission.OAUTH_APPLICATION_CREATE);
        validateRedirectUris(request.redirectUris());
        Instant now = clock.instant();
        String clientId = "pgo_" + crypto.randomToken(18);
        String rawSecret = crypto.randomToken(48);
        OAuthApplication application = applicationRepository.saveAndFlush(OAuthApplication.register(
                principal.organizationId(), request.name(), request.description(), clientId,
                crypto.hmacSha256(rawSecret), rawSecret.substring(0, 8),
                request.redirectUris(), request.allowedOrigins(), request.scopes(), principal.userId(), now));
        audit(principal, "oauth_application.registered", application.getId(), Map.of("clientId", clientId));
        return new CreatedApplicationView(toView(application), rawSecret);
    }

    @Transactional(readOnly = true)
    public List<ApplicationView> list(AuthenticatedUser principal) {
        authorizationService.requirePermission(principal, Permission.OAUTH_APPLICATION_READ);
        return applicationRepository.findByOrganizationIdOrderByCreatedAtDesc(principal.organizationId())
                .stream().map(this::toView).toList();
    }

    @Transactional
    public ApplicationView update(AuthenticatedUser principal, UUID applicationId,
            CreateApplicationRequest request) {
        authorizationService.requirePermission(principal, Permission.OAUTH_APPLICATION_UPDATE);
        validateRedirectUris(request.redirectUris());
        OAuthApplication application = require(principal, applicationId);
        application.update(request.name(), request.description(), request.redirectUris(),
                request.allowedOrigins(), request.scopes());
        applicationRepository.saveAndFlush(application);
        audit(principal, "oauth_application.updated", applicationId, Map.of());
        return toView(application);
    }

    @Transactional
    public ApplicationView verify(AuthenticatedUser principal, UUID applicationId,
            VerifyApplicationRequest request) {
        authorizationService.requirePermission(principal, Permission.OAUTH_APPLICATION_UPDATE);
        OAuthApplication application = require(principal, applicationId);
        application.verify(request.reference().trim(), clock.instant());
        applicationRepository.saveAndFlush(application);
        audit(principal, "oauth_application.verified", applicationId, Map.of());
        return toView(application);
    }

    /** Issues a new secret; the previous one stops working immediately. */
    @Transactional
    public CreatedApplicationView rotateSecret(AuthenticatedUser principal, UUID applicationId) {
        authorizationService.requirePermission(principal, Permission.OAUTH_APPLICATION_ROTATE);
        OAuthApplication application = require(principal, applicationId);
        String rawSecret = crypto.randomToken(48);
        application.rotateSecret(crypto.hmacSha256(rawSecret), rawSecret.substring(0, 8));
        applicationRepository.saveAndFlush(application);
        revokeGrants(application, "client_secret_rotated");
        audit(principal, "oauth_application.secret_rotated", applicationId, Map.of());
        return new CreatedApplicationView(toView(application), rawSecret);
    }

    @Transactional
    public ApplicationView suspend(AuthenticatedUser principal, UUID applicationId) {
        authorizationService.requirePermission(principal, Permission.OAUTH_APPLICATION_SUSPEND);
        OAuthApplication application = require(principal, applicationId);
        application.suspend(clock.instant());
        applicationRepository.saveAndFlush(application);
        revokeGrants(application, "application_suspended");
        audit(principal, "oauth_application.suspended", applicationId, Map.of());
        return toView(application);
    }

    @Transactional
    public ApplicationView resume(AuthenticatedUser principal, UUID applicationId) {
        authorizationService.requirePermission(principal, Permission.OAUTH_APPLICATION_SUSPEND);
        OAuthApplication application = require(principal, applicationId);
        application.resume(clock.instant());
        applicationRepository.saveAndFlush(application);
        audit(principal, "oauth_application.resumed", applicationId, Map.of());
        return toView(application);
    }

    @Transactional
    public ApplicationView revoke(AuthenticatedUser principal, UUID applicationId) {
        authorizationService.requirePermission(principal, Permission.OAUTH_APPLICATION_REVOKE);
        OAuthApplication application = require(principal, applicationId);
        application.revoke(clock.instant());
        applicationRepository.saveAndFlush(application);
        revokeGrants(application, "application_revoked");
        audit(principal, "oauth_application.revoked", applicationId, Map.of());
        return toView(application);
    }

    /**
     * Revoking, suspending or rotating an application kills every live grant it
     * issued, for every user it ever granted access to. Scoping this to the
     * creating user would leave tokens issued to consenting users alive.
     */
    private void revokeGrants(OAuthApplication application, String reason) {
        Instant now = clock.instant();
        refreshTokenRepository.findByApplicationId(application.getId())
                .forEach(token -> {
                    if (token.isUsable(now)) {
                        token.revoke(now, reason);
                        refreshTokenRepository.save(token);
                    }
                });
        accessTokenRepository.findByApplicationId(application.getId())
                .forEach(token -> {
                    if (token.isActive(now)) {
                        token.revoke(now, reason);
                        accessTokenRepository.save(token);
                    }
                });
    }

    private void validateRedirectUris(Set<String> redirectUris) {
        if (redirectUris == null || redirectUris.isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REDIRECT_URI",
                    "At least one redirect URI is required.");
        }
        for (String uri : redirectUris) {
            if (!RedirectUriPolicy.isValidRedirectUri(uri)) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REDIRECT_URI",
                        "Redirect URIs must be absolute https URIs without wildcards or fragments. "
                                + "http is allowed only for loopback addresses.");
            }
        }
    }

    private OAuthApplication require(AuthenticatedUser principal, UUID applicationId) {
        return applicationRepository.findByIdAndOrganizationId(applicationId, principal.organizationId())
                .orElseThrow(() -> new ResourceNotFoundException("OAuth application"));
    }

    private void audit(AuthenticatedUser principal, String action, UUID applicationId, Map<String, ?> metadata) {
        auditService.append(principal.organizationId(), principal.userId(), action,
                "oauth_application", applicationId.toString(), RequestContext.currentRequestId(), metadata);
    }

    private ApplicationView toView(OAuthApplication application) {
        return new ApplicationView(application.getId(), application.getName(), application.getDescription(),
                application.getClientId(), application.getClientSecretHint(),
                application.getClientSecretVersion(), application.redirectUriSet(),
                application.allowedOriginSet(), application.scopeSet(), application.getStatus(),
                application.getProjectId(), application.getEnvironmentId(), application.getVerifiedAt(),
                application.getVerificationReference(), application.getStatusChangedAt(),
                application.getCreatedAt(), application.getUpdatedAt());
    }
}
