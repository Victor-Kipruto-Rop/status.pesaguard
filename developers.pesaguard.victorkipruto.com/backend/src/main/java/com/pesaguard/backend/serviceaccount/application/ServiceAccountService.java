package com.pesaguard.backend.serviceaccount.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import com.pesaguard.backend.organization.domain.MembershipStatus;
import com.pesaguard.backend.organization.domain.OrganizationStatus;
import com.pesaguard.backend.organization.infrastructure.OrganizationMembershipRepository;
import com.pesaguard.backend.organization.infrastructure.OrganizationRepository;
import com.pesaguard.backend.oauth.application.OAuthTokenService;
import com.pesaguard.backend.rbac.application.AuthorizationService;
import com.pesaguard.backend.rbac.domain.Permission;
import com.pesaguard.backend.security.credentials.CredentialCryptoService;
import com.pesaguard.backend.security.principals.AuthenticatedUser;
import com.pesaguard.backend.serviceaccount.api.CreatedServiceAccountView;
import com.pesaguard.backend.serviceaccount.api.ServiceAccountRequest;
import com.pesaguard.backend.serviceaccount.api.ServiceAccountTokenView;
import com.pesaguard.backend.serviceaccount.api.ServiceAccountTokenRequest;
import com.pesaguard.backend.serviceaccount.api.ServiceAccountView;
import com.pesaguard.backend.serviceaccount.domain.ServiceAccount;
import com.pesaguard.backend.serviceaccount.domain.ServiceAccountAccessToken;
import com.pesaguard.backend.serviceaccount.infrastructure.ServiceAccountAccessTokenRepository;
import com.pesaguard.backend.serviceaccount.infrastructure.ServiceAccountRepository;

@Service
public class ServiceAccountService {

    private final ServiceAccountRepository accountRepository;
    private final ServiceAccountAccessTokenRepository tokenRepository;
    private final AuthorizationService authorizationService;
    private final CredentialCryptoService crypto;
    private final AuditService auditService;
    private final OAuthTokenService oauthTokenService;
    private final OrganizationMembershipRepository membershipRepository;
    private final OrganizationRepository organizationRepository;
    private final OAuthProperties oauthProperties;
    private final Clock clock;

    public ServiceAccountService(ServiceAccountRepository accountRepository,
            ServiceAccountAccessTokenRepository tokenRepository,
            AuthorizationService authorizationService, CredentialCryptoService crypto,
            AuditService auditService, OAuthTokenService oauthTokenService,
            OrganizationMembershipRepository membershipRepository, OrganizationRepository organizationRepository,
            OAuthProperties oauthProperties, Clock clock) {
        this.accountRepository = accountRepository;
        this.tokenRepository = tokenRepository;
        this.authorizationService = authorizationService;
        this.crypto = crypto;
        this.auditService = auditService;
        this.oauthTokenService = oauthTokenService;
        this.membershipRepository = membershipRepository;
        this.organizationRepository = organizationRepository;
        this.oauthProperties = oauthProperties;
        this.clock = clock;
    }

    @Transactional
    public CreatedServiceAccountView create(AuthenticatedUser principal, ServiceAccountRequest request) {
        authorizationService.requirePermission(principal, Permission.CREDENTIAL_CREATE);
        validateScopes(principal, request.scopes());
        String rawSecret = "pgss_" + crypto.randomToken(36);
        ServiceAccount account = accountRepository.saveAndFlush(ServiceAccount.create(
                principal.organizationId(), request.name(), request.description(),
                "pgsa_" + crypto.randomToken(18), crypto.hmacSha256(rawSecret),
                rawSecret.substring(0, 12), request.scopes(), principal.userId()));
        audit(principal, "service_account.created", account, Map.of("scopeCount", request.scopes().size()));
        return new CreatedServiceAccountView(toView(account), rawSecret);
    }

    @Transactional(readOnly = true)
    public List<ServiceAccountView> list(AuthenticatedUser principal) {
        authorizationService.requirePermission(principal, Permission.CREDENTIAL_READ);
        return accountRepository.findByOrganizationIdOrderByCreatedAtDesc(principal.organizationId())
                .stream().map(ServiceAccountService::toView).toList();
    }

    @Transactional
    public ServiceAccountView update(
            AuthenticatedUser principal, UUID accountId, ServiceAccountRequest request) {
        authorizationService.requirePermission(principal, Permission.CREDENTIAL_UPDATE);
        validateScopes(principal, request.scopes());
        ServiceAccount account = require(principal, accountId);
        account.update(request.name(), request.description(), request.scopes());
        revokeAccessTokens(account, clock.instant());
        ServiceAccount saved = accountRepository.saveAndFlush(account);
        audit(principal, "service_account.updated", saved, Map.of("scopeCount", request.scopes().size()));
        return toView(saved);
    }

    @Transactional
    public CreatedServiceAccountView rotateSecret(AuthenticatedUser principal, UUID accountId) {
        authorizationService.requirePermission(principal, Permission.CREDENTIAL_ROTATE);
        ServiceAccount account = require(principal, accountId);
        String rawSecret = "pgss_" + crypto.randomToken(36);
        account.rotateSecret(crypto.hmacSha256(rawSecret), rawSecret.substring(0, 12));
        revokeAccessTokens(account, clock.instant());
        ServiceAccount saved = accountRepository.saveAndFlush(account);
        audit(principal, "service_account.secret_rotated", saved, Map.of());
        return new CreatedServiceAccountView(toView(saved), rawSecret);
    }

    @Transactional
    public ServiceAccountView suspend(AuthenticatedUser principal, UUID accountId) {
        authorizationService.requirePermission(principal, Permission.CREDENTIAL_UPDATE);
        ServiceAccount account = require(principal, accountId);
        account.suspend();
        revokeAccessTokens(account, clock.instant());
        ServiceAccount saved = accountRepository.saveAndFlush(account);
        audit(principal, "service_account.suspended", saved, Map.of());
        return toView(saved);
    }

    @Transactional
    public ServiceAccountView resume(AuthenticatedUser principal, UUID accountId) {
        authorizationService.requirePermission(principal, Permission.CREDENTIAL_UPDATE);
        ServiceAccount account = require(principal, accountId);
        account.resume();
        ServiceAccount saved = accountRepository.saveAndFlush(account);
        audit(principal, "service_account.resumed", saved, Map.of());
        return toView(saved);
    }

    @Transactional
    public void revoke(AuthenticatedUser principal, UUID accountId) {
        authorizationService.requirePermission(principal, Permission.CREDENTIAL_REVOKE);
        ServiceAccount account = require(principal, accountId);
        account.revoke(clock.instant());
        revokeAccessTokens(account, clock.instant());
        accountRepository.saveAndFlush(account);
        audit(principal, "service_account.revoked", account, Map.of());
    }

    @Transactional
    public ServiceAccountTokenView issueAccessToken(ServiceAccountTokenRequest request, String remoteAddress) {
        oauthTokenService.enforceClientThrottle(remoteAddress);
        if (!"client_credentials".equals(request.grantType())) {
            throw invalidClient();
        }
        ServiceAccount account = accountRepository.findByClientId(request.clientId().trim())
                .filter(candidate -> "ACTIVE".equals(candidate.getStatus()))
                .filter(candidate -> secretsMatch(candidate, request.clientSecret()))
                .orElseThrow(ServiceAccountService::invalidClient);
        Instant now = clock.instant();
        String rawToken = "pgsat_" + crypto.randomToken(32);
        Instant expiresAt = now.plus(oauthProperties.accessTokenTtl());
        tokenRepository.saveAndFlush(ServiceAccountAccessToken.issue(
                account.getId(), crypto.hmacSha256(rawToken), account.scopeSet(), expiresAt));
        return new ServiceAccountTokenView(rawToken, "Bearer",
                Math.toIntExact(oauthProperties.accessTokenTtl().toSeconds()), account.scopeSet());
    }

    @Transactional(readOnly = true)
    public Optional<AuthenticatedUser> authenticateAccessToken(String rawToken) {
        if (rawToken == null || !rawToken.startsWith("pgsat_")) return Optional.empty();
        String tokenHash = crypto.hmacSha256(rawToken);
        return tokenRepository.findByTokenHash(tokenHash)
                .filter(token -> token.isActive(clock.instant()))
                .flatMap(token -> accountRepository.findById(token.getServiceAccountId())
                        .filter(account -> "ACTIVE".equals(account.getStatus()))
                        .filter(account -> membershipRepository
                                .findByOrganizationIdAndUserId(account.getOrganizationId(), account.getCreatedBy())
                                .filter(membership -> membership.getStatus() == MembershipStatus.ACTIVE)
                                .isPresent())
                        .filter(account -> organizationRepository.findById(account.getOrganizationId())
                                .filter(organization -> organization.getStatus() == OrganizationStatus.ACTIVE)
                                .isPresent())
                        .map(account -> new AuthenticatedUser(account.getCreatedBy(), account.getOrganizationId(),
                                token.getId(), "", "Service account: " + account.getName(),
                                Set.of(), OrganizationStatus.ACTIVE, true, token.scopeSet())));
    }

    private void validateScopes(AuthenticatedUser principal, Set<String> requestedScopes) {
        Set<Permission> grantable = authorizationService.effectivePermissions(principal);
        for (String scope : requestedScopes) {
            Permission permission = Permission.parse(scope)
                    .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST,
                            "INVALID_SERVICE_ACCOUNT_SCOPE", "Unknown service-account permission scope."));
            if (!grantable.contains(permission)) {
                throw new BusinessException(HttpStatus.FORBIDDEN, "PERMISSION_DENIED",
                        "A service account cannot be granted permissions the creator does not hold.");
            }
        }
    }

    private ServiceAccount require(AuthenticatedUser principal, UUID accountId) {
        return accountRepository.findByIdAndOrganizationId(accountId, principal.organizationId())
                .orElseThrow(() -> new ResourceNotFoundException("Service account"));
    }

    private boolean secretsMatch(ServiceAccount account, String secret) {
        if (secret == null || secret.isBlank()) return false;
        String actual = crypto.hmacSha256(secret.trim());
        return java.security.MessageDigest.isEqual(
                actual.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                account.getClientSecretHash().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private void revokeAccessTokens(ServiceAccount account, Instant now) {
        for (ServiceAccountAccessToken token : tokenRepository.findByServiceAccountId(account.getId())) {
            token.revoke(now);
            tokenRepository.save(token);
        }
    }

    private void audit(AuthenticatedUser principal, String action, ServiceAccount account, Map<String, ?> metadata) {
        auditService.append(principal.organizationId(), principal.userId(), action, "service_account",
                account.getId().toString(), RequestContext.currentRequestId(), metadata);
    }

    private static BusinessException invalidClient() {
        return new BusinessException(HttpStatus.UNAUTHORIZED, "INVALID_CLIENT",
                "Client authentication failed.");
    }

    private static ServiceAccountView toView(ServiceAccount account) {
        return new ServiceAccountView(account.getId(), account.getName(), account.getDescription(),
                account.getClientId(), account.getClientSecretHint(), account.scopeSet(),
                account.getStatus(), account.getCreatedAt());
    }
}
