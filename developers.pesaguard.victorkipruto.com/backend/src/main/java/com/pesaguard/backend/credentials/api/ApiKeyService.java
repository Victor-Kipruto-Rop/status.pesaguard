package com.pesaguard.backend.credentials.api;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pesaguard.backend.audit.application.AuditService;
import com.pesaguard.backend.common.api.RequestContext;
import com.pesaguard.backend.common.exception.ResourceNotFoundException;
import com.pesaguard.backend.environment.infrastructure.ProjectEnvironmentRepository;
import com.pesaguard.backend.environment.domain.EnvironmentLimits;
import com.pesaguard.backend.environment.application.EnvironmentAccessPolicyService;
import com.pesaguard.backend.environment.domain.EnvironmentPermission;
import com.pesaguard.backend.rbac.domain.Permission;
import com.pesaguard.backend.project.domain.ProjectStatus;
import com.pesaguard.backend.project.application.ProjectAuthorization;
import com.pesaguard.backend.project.infrastructure.ProjectRepository;
import com.pesaguard.backend.security.authentication.IpRangeMatcher;
import com.pesaguard.backend.security.credentials.SecretEncryptionService;
import com.pesaguard.backend.security.principals.AuthenticatedUser;
import com.pesaguard.backend.scopes.application.ScopeRegistryService;
import com.pesaguard.backend.events.application.DeveloperEventEmitter;

@Service
public class ApiKeyService {

    private static final Duration MAX_LIFETIME = Duration.ofDays(366);

    private final ApiKeyRepository apiKeyRepository;
    private final ProjectRepository projectRepository;
    private final ProjectEnvironmentRepository environmentRepository;
    private final ApiKeyGenerator generator;
    private final AuditService auditService;
    private final com.pesaguard.backend.rbac.application.AuthorizationService authorizationService;
    private final com.pesaguard.backend.environment.infrastructure.EnvironmentLimitsRepository environmentLimitsRepository;
    private final ApiKeyHistoryRepository historyRepository;
    private final IpRangeMatcher ipRangeMatcher;
    private final ScopeRegistryService scopeRegistryService;
    private final ProjectAuthorization projectAuthorization;
    private final ApiKeyCreationIdempotencyRepository idempotencyRepository;
    private final SecretEncryptionService secretEncryption;
    private final DeveloperEventEmitter developerEventEmitter;
    private final EnvironmentAccessPolicyService environmentAccessPolicyService;
    private final PipelineApiKeySynchronizer pipelineApiKeySynchronizer;

    public ApiKeyService(ApiKeyRepository apiKeyRepository, ProjectRepository projectRepository,
            ProjectEnvironmentRepository environmentRepository, ApiKeyGenerator generator,
            AuditService auditService,
            com.pesaguard.backend.rbac.application.AuthorizationService authorizationService,
            com.pesaguard.backend.environment.infrastructure.EnvironmentLimitsRepository environmentLimitsRepository,
            ApiKeyHistoryRepository historyRepository, IpRangeMatcher ipRangeMatcher,
            ScopeRegistryService scopeRegistryService, ProjectAuthorization projectAuthorization,
            ApiKeyCreationIdempotencyRepository idempotencyRepository,
            SecretEncryptionService secretEncryption,
            DeveloperEventEmitter developerEventEmitter,
            EnvironmentAccessPolicyService environmentAccessPolicyService,
            PipelineApiKeySynchronizer pipelineApiKeySynchronizer) {
        this.apiKeyRepository = apiKeyRepository;
        this.projectRepository = projectRepository;
        this.environmentRepository = environmentRepository;
        this.generator = generator;
        this.auditService = auditService;
        this.authorizationService = authorizationService;
        this.environmentLimitsRepository = environmentLimitsRepository;
        this.historyRepository = historyRepository;
        this.ipRangeMatcher = ipRangeMatcher;
        this.scopeRegistryService = scopeRegistryService;
        this.projectAuthorization = projectAuthorization;
        this.idempotencyRepository = idempotencyRepository;
        this.secretEncryption = secretEncryption;
        this.developerEventEmitter = developerEventEmitter;
        this.environmentAccessPolicyService = environmentAccessPolicyService;
        this.pipelineApiKeySynchronizer = pipelineApiKeySynchronizer;
    }

    @Transactional
    public CreatedApiKeyView issue(AuthenticatedUser principal, UUID projectId, UUID environmentId,
            CreateApiKeyRequest request, Instant now) {
        return issue(principal, projectId, environmentId, request, now, null);
    }

    @Transactional
    public CreatedApiKeyView issue(AuthenticatedUser principal, UUID projectId, UUID environmentId,
            CreateApiKeyRequest request, Instant now, String idempotencyKey) {
        authorizationService.requirePermission(principal, Permission.CREDENTIAL_CREATE);
        var project = projectRepository.findByIdAndOrganizationId(projectId, principal.organizationId())
                .orElseThrow(() -> new ResourceNotFoundException("Project"));
        projectAuthorization.requireProjectManage(principal, projectId);
        if (project.getStatus() != ProjectStatus.ACTIVE) {
            throw new ResourceNotFoundException("Project");
        }
        var environment = environmentRepository.findByIdAndOrganizationIdAndProjectIdForUpdate(
                        environmentId, principal.organizationId(), projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Environment"));
        String baseUrl = com.pesaguard.backend.environment.api.EnvironmentApiBaseUrls.forType(environment.getType());
        if (environment.getStatus() != com.pesaguard.backend.environment.domain.EnvironmentStatus.ACTIVE) {
            throw new ResourceNotFoundException("Environment");
        }
        environmentAccessPolicyService.requireAccess(principal, environment,
                EnvironmentPermission.ROTATE_CREDENTIALS);
        Duration lifetime = parseLifetime(request.expiresIn());
        scopeRegistryService.validate(request.scopes());
        ipRangeMatcher.validate(request.ipAllowlist());

        String keyHash = null;
        String fingerprint = null;
        ApiKeyCreationIdempotency savedIdempotency = null;
        if (idempotencyKey != null) {
            String normalizedIdempotencyKey = idempotencyKey.trim();
            if (!normalizedIdempotencyKey.matches("[A-Za-z0-9._:-]{8,128}")) {
                throw new com.pesaguard.backend.common.exception.BusinessException(HttpStatus.BAD_REQUEST,
                        "IDEMPOTENCY_KEY_INVALID",
                        "Idempotency-Key must contain 8 to 128 letters, digits, dots, underscores, colons, or hyphens.");
            }
            keyHash = generator.hash(normalizedIdempotencyKey);
            fingerprint = issueFingerprint(projectId, environmentId, request);
            savedIdempotency = idempotencyRepository
                    .findByOrganizationIdAndUserIdAndIdempotencyKeyHash(
                            principal.organizationId(), principal.userId(), keyHash)
                    .orElse(null);
            if (savedIdempotency != null && !savedIdempotency.isExpired(now)) {
                if (!savedIdempotency.getRequestFingerprint().equals(fingerprint)) {
                    throw new com.pesaguard.backend.common.exception.ResourceConflictException(
                            "IDEMPOTENCY_KEY_REUSED",
                            "This Idempotency-Key was already used for a different API-key request.");
                }
                ApiKey replayedKey = apiKeyRepository.findById(savedIdempotency.getApiKeyId())
                        .orElseThrow(() -> new IllegalStateException(
                                "Idempotent API-key creation references a missing key."));
                if (replayedKey.getStatus() != ApiKeyStatus.ACTIVE
                        || replayedKey.getExpiresAt() != null && !replayedKey.getExpiresAt().isAfter(now)) {
                    throw new com.pesaguard.backend.common.exception.ResourceConflictException(
                            "IDEMPOTENCY_KEY_RESULT_UNAVAILABLE",
                            "The API key created by this Idempotency-Key is no longer active. Create a new key with a new Idempotency-Key.");
                }
                CreatedApiKeyView replay = savedIdempotency.replay(secretEncryption);
                pipelineApiKeySynchronizer.synchronize(
                        replayedKey, generator.pipelineHash(replay.key()));
                return replay;
            }
        }

        enforceApiKeyLimit(principal, projectId, environmentId, null);
        ApiKeyGenerator.GeneratedKey generated = generator.generate(environment.getType());
        ApiKey key = ApiKey.create(
                principal.organizationId(), projectId, environmentId, request.name().trim(),
                generated.prefix(), generator.hash(generated.rawKey()), ScopeCodec.encode(request.scopes()),
                now.plus(lifetime), principal.userId());
        key.storeEncryptedSecret(secretEncryption.encrypt(generated.rawKey()));
        key.restrictToIps(request.ipAllowlist());
        key = apiKeyRepository.saveAndFlush(key);
        recordHistory(principal, key, ApiKeyStatus.CREATED, ApiKeyStatus.CREATED, "api_key.created", null);
        key.activate();
        apiKeyRepository.saveAndFlush(key);
        pipelineApiKeySynchronizer.synchronize(key, generator.pipelineHash(generated.rawKey()));
        if (keyHash != null) {
            Instant replayExpiresAt = now.plus(Duration.ofHours(24));
            if (savedIdempotency == null) {
                savedIdempotency = new ApiKeyCreationIdempotency(principal.organizationId(),
                        principal.userId(), keyHash, fingerprint, key,
                        secretEncryption.encrypt(generated.rawKey()), replayExpiresAt, baseUrl);
            } else {
                savedIdempotency.replace(fingerprint, key,
                        secretEncryption.encrypt(generated.rawKey()), replayExpiresAt, baseUrl);
            }
            idempotencyRepository.saveAndFlush(savedIdempotency);
        }
        recordHistory(principal, key, ApiKeyStatus.CREATED, ApiKeyStatus.ACTIVE, "api_key.activated", null);
        developerEventEmitter.apiKeyCreated(key);
        auditService.append(principal.organizationId(), principal.userId(), "api_key.issued", "api_key",
                key.getId().toString(), RequestContext.currentRequestId(),
                Map.of("projectId", projectId.toString(), "environmentId", environmentId.toString()));
        return new CreatedApiKeyView(key.getId(), key.getName(), generated.rawKey(), key.getKeyPrefix(),
                request.scopes(), key.getExpiresAt(), baseUrl);
    }

    private String issueFingerprint(UUID projectId, UUID environmentId, CreateApiKeyRequest request) {
        String canonical = String.join("\n", projectId.toString(), environmentId.toString(),
                request.name().trim(), ScopeCodec.encode(request.scopes()), request.expiresIn().trim(),
                request.ipAllowlist().stream().sorted().collect(java.util.stream.Collectors.joining(",")));
        return generator.hash(canonical);
    }

    @Transactional(readOnly = true)
    public List<ApiKeyView> list(AuthenticatedUser principal, UUID projectId, UUID environmentId) {
        authorizationService.requirePermission(principal, Permission.CREDENTIAL_READ);
        requireEnvironment(principal, projectId, environmentId, false);
        java.time.Instant now = java.time.Instant.now();
        return apiKeyRepository.findByOrganizationIdAndProjectIdAndEnvironmentIdOrderByCreatedAtDesc(
                        principal.organizationId(), projectId, environmentId)
                .stream()
                .filter(key -> key.effectiveStatus(now) == ApiKeyStatus.ACTIVE
                        || key.effectiveStatus(now) == ApiKeyStatus.SUSPENDED)
                .map(key -> toView(key, now)).toList();
    }

    @Transactional
    public void revoke(AuthenticatedUser principal, UUID projectId, UUID environmentId, UUID keyId, Instant now) {
        authorizationService.requirePermission(principal, Permission.CREDENTIAL_REVOKE);
        requireEnvironment(principal, projectId, environmentId, true);
        ApiKey key = requireKey(principal, projectId, environmentId, keyId);
        ApiKeyStatus from = key.getStatus();
        boolean changed = from != ApiKeyStatus.REVOKED && from != ApiKeyStatus.COMPROMISED;
        key.revoke(now);
        apiKeyRepository.saveAndFlush(key);
        pipelineApiKeySynchronizer.revoke(key);
        if (changed) {
            recordHistory(principal, key, from, ApiKeyStatus.REVOKED, "api_key.revoked", null);
            auditService.append(principal.organizationId(), principal.userId(), "api_key.revoked", "api_key",
                    keyId.toString(), RequestContext.currentRequestId(), Map.of());
            developerEventEmitter.apiKeyRevoked(key, "revoked_by_user");
        }
    }

    /**
     * Revokes credentials issued by a developer without deleting key/history rows.
     *
     * <p>The append-only per-key history records the lifecycle revocation while
     * shared project and organization records remain intact.
     */
    @Transactional
    public int revokeCreatedByUserId(UUID userId, Instant now) {
        int revoked = 0;
        for (ApiKey key : apiKeyRepository.findByCreatedByOrderByCreatedAtDesc(userId)) {
            ApiKeyStatus previous = key.getStatus();
            if (previous.isTerminal()) {
                continue;
            }
            key.revoke(now);
            apiKeyRepository.saveAndFlush(key);
            pipelineApiKeySynchronizer.revoke(key);
            historyRepository.save(ApiKeyHistory.record(
                    key.getOrganizationId(), key.getId(), previous, ApiKeyStatus.REVOKED,
                    "api_key.revoked", userId, "Account deactivated or deleted", now));
            auditService.append(key.getOrganizationId(), userId, "api_key.revoked", "api_key",
                    key.getId().toString(), RequestContext.currentRequestId(),
                    Map.of("reason", "account_lifecycle"));
            developerEventEmitter.apiKeyRevoked(key, "account_lifecycle");
            revoked++;
        }
        return revoked;
    }

    @Transactional
    public ApiKeyView updateRestrictions(AuthenticatedUser principal, UUID projectId, UUID environmentId,
            UUID keyId, UpdateApiKeyRestrictionsRequest request, Instant now) {
        authorizationService.requirePermission(principal, Permission.CREDENTIAL_UPDATE);
        requireEnvironment(principal, projectId, environmentId, true);
        ApiKey key = requireKey(principal, projectId, environmentId, keyId);
        if (key.effectiveStatus(now).isTerminal()) {
            throw new com.pesaguard.backend.common.exception.BusinessException(
                    HttpStatus.CONFLICT, "API_KEY_TERMINAL",
                    "Restrictions cannot be changed for a revoked, expired, or compromised key.");
        }
        ipRangeMatcher.validate(request.ipAllowlist());
        key.restrictToIps(request.ipAllowlist());
        apiKeyRepository.saveAndFlush(key);
        pipelineApiKeySynchronizer.synchronize(key, null);
        recordHistory(principal, key, key.getStatus(), key.getStatus(), "api_key.restricted",
                "IP/CIDR restrictions updated");
        auditService.append(principal.organizationId(), principal.userId(), "api_key.restricted", "api_key",
                keyId.toString(), RequestContext.currentRequestId(),
                Map.of("cidrCount", Integer.toString(request.ipAllowlist().size())));
        return toView(key, now);
    }

    @Transactional
    public ApiKeyView rename(AuthenticatedUser principal, UUID projectId, UUID environmentId,
            UUID keyId, RenameApiKeyRequest request, Instant now) {
        authorizationService.requirePermission(principal, Permission.CREDENTIAL_UPDATE);
        requireEnvironment(principal, projectId, environmentId, true);
        ApiKey key = requireKey(principal, projectId, environmentId, keyId);
        String name = request.name().trim();
        if (name.isEmpty()) {
            throw new com.pesaguard.backend.common.exception.BusinessException(
                    HttpStatus.BAD_REQUEST, "API_KEY_NAME_INVALID", "API-key name must not be blank.");
        }
        String previousName = key.getName();
        key.rename(name);
        apiKeyRepository.saveAndFlush(key);
        recordHistory(principal, key, key.getStatus(), key.getStatus(), "api_key.renamed",
                "Renamed from " + previousName);
        auditService.append(principal.organizationId(), principal.userId(), "api_key.renamed", "api_key",
                keyId.toString(), RequestContext.currentRequestId(), Map.of());
        return toView(key, now);
    }

    @Transactional
    public ApiKeyView markCompromised(AuthenticatedUser principal, UUID projectId, UUID environmentId,
            UUID keyId, Instant now) {
        authorizationService.requirePermission(principal, Permission.CREDENTIAL_REVOKE);
        requireEnvironment(principal, projectId, environmentId, true);
        ApiKey key = requireKey(principal, projectId, environmentId, keyId);
        ApiKeyStatus from = key.getStatus();
        if (from.isTerminal() && from != ApiKeyStatus.COMPROMISED) {
            throw new com.pesaguard.backend.common.exception.BusinessException(
                    HttpStatus.CONFLICT, "API_KEY_TERMINAL",
                    "A revoked or expired key cannot be marked compromised.");
        }
        key.markCompromised(now);
        apiKeyRepository.saveAndFlush(key);
        pipelineApiKeySynchronizer.synchronize(key, null);
        if (from != ApiKeyStatus.COMPROMISED) {
            recordHistory(principal, key, from, ApiKeyStatus.COMPROMISED, "api_key.compromised",
                    "Emergency credential revocation");
            auditService.append(principal.organizationId(), principal.userId(), "api_key.compromised", "api_key",
                    keyId.toString(), RequestContext.currentRequestId(), Map.of());
            developerEventEmitter.apiKeyRevoked(key, "compromised");
        }
        return toView(key, now);
    }

    @Transactional
    public ApiKeyView suspend(AuthenticatedUser principal, UUID projectId, UUID environmentId, UUID keyId,
            Instant now) {
        authorizationService.requirePermission(principal, Permission.CREDENTIAL_REVOKE);
        requireEnvironment(principal, projectId, environmentId, true);
        ApiKey key = requireKey(principal, projectId, environmentId, keyId);
        ApiKeyStatus from = key.getStatus();
        key.suspend(now);
        apiKeyRepository.saveAndFlush(key);
        pipelineApiKeySynchronizer.suspend(key);
        recordHistory(principal, key, from, ApiKeyStatus.SUSPENDED, "api_key.suspended", null);
        developerEventEmitter.apiKeySuspended(key);
        return toView(key, now);
    }

    @Transactional
    public ApiKeyView resume(AuthenticatedUser principal, UUID projectId, UUID environmentId, UUID keyId) {
        authorizationService.requirePermission(principal, Permission.CREDENTIAL_REVOKE);
        requireEnvironment(principal, projectId, environmentId, true);
        ApiKey key = requireKey(principal, projectId, environmentId, keyId);
        ApiKeyStatus from = key.getStatus();
        key.resume();
        apiKeyRepository.saveAndFlush(key);
        pipelineApiKeySynchronizer.synchronize(key, null);
        recordHistory(principal, key, from, ApiKeyStatus.ACTIVE, "api_key.resumed", null);
        return toView(key, Instant.now());
    }

    /**
     * Rotation issues a brand new secret and revokes the previous key in the same
     * transaction. There is no overlap window: the old secret stops working the
     * moment the new one is returned, which is the safe default for a credential.
     */
    @Transactional
    public CreatedApiKeyView rotate(AuthenticatedUser principal, UUID projectId, UUID environmentId,
            UUID keyId, Instant now) {
        authorizationService.requirePermission(principal, Permission.CREDENTIAL_ROTATE);
        requireEnvironment(principal, projectId, environmentId, true);
        var environment = environmentRepository.findByIdAndOrganizationIdAndProjectIdForUpdate(
                        environmentId, principal.organizationId(), projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Environment"));
        ApiKey current = requireKey(principal, projectId, environmentId, keyId);
        if (current.getStatus().isTerminal() || current.getExpiresAt() == null
                || !current.getExpiresAt().isAfter(now)) {
            throw new com.pesaguard.backend.common.exception.BusinessException(
                    org.springframework.http.HttpStatus.CONFLICT, "API_KEY_NOT_ROTATABLE",
                    "A revoked or expired key cannot be rotated.");
        }
        enforceApiKeyLimit(principal, projectId, environmentId, current.getId());

        ApiKeyStatus previousStatus = current.getStatus();
        current.revoke(now);
        apiKeyRepository.saveAndFlush(current);
        pipelineApiKeySynchronizer.revoke(current);
        ApiKeyGenerator.GeneratedKey generated = generator.generate(environment.getType());
        ApiKey rotated = apiKeyRepository.saveAndFlush(ApiKey.create(
                principal.organizationId(), projectId, environmentId, current.getName(),
                generated.prefix(), generator.hash(generated.rawKey()),
                ScopeCodec.encode(current.scopeSet()), current.getExpiresAt(), principal.userId()));
        rotated.storeEncryptedSecret(secretEncryption.encrypt(generated.rawKey()));
        rotated.restrictToIps(ScopeCodec.decode(current.getIpAllowlist()));
        rotated.markRotatedFrom(current.getId());
        rotated.activate();
        apiKeyRepository.saveAndFlush(rotated);
        pipelineApiKeySynchronizer.synchronize(rotated, generator.pipelineHash(generated.rawKey()));

        recordHistory(principal, current, previousStatus, ApiKeyStatus.REVOKED, "api_key.rotated_out",
                "replaced by " + rotated.getId());
        recordHistory(principal, rotated, null, ApiKeyStatus.ACTIVE, "api_key.rotated_in",
                "replaces " + current.getId());
        developerEventEmitter.apiKeyRotated(current, rotated);
        auditService.append(principal.organizationId(), principal.userId(), "api_key.rotated", "api_key",
                current.getId().toString(), RequestContext.currentRequestId(),
                Map.of("rotatedKeyId", rotated.getId().toString()));
        return new CreatedApiKeyView(rotated.getId(), rotated.getName(), generated.rawKey(),
                generated.prefix(), rotated.scopeSet(), rotated.getExpiresAt(),
                com.pesaguard.backend.environment.api.EnvironmentApiBaseUrls.forType(environment.getType()));
    }

    @Transactional(readOnly = true)
    public List<ApiKeyHistoryView> history(AuthenticatedUser principal, UUID projectId,
            UUID environmentId, UUID keyId) {
        authorizationService.requirePermission(principal, Permission.CREDENTIAL_READ);
        requireEnvironment(principal, projectId, environmentId, false);
        requireKey(principal, projectId, environmentId, keyId);
        return historyRepository.findByApiKeyIdOrderByCreatedAtDesc(keyId).stream()
                .map(entry -> new ApiKeyHistoryView(entry.getId(), entry.getApiKeyId(), entry.getAction(),
                        entry.getFromStatus() == null ? null : entry.getFromStatus().name(),
                        entry.getToStatus().name(), entry.getActorUserId(), entry.getReason(),
                        entry.getCreatedAt()))
                .toList();
    }

    private ApiKey requireKey(AuthenticatedUser principal, UUID projectId, UUID environmentId, UUID keyId) {
        return apiKeyRepository
                .findByIdAndOrganizationIdAndProjectIdAndEnvironmentId(
                        keyId, principal.organizationId(), projectId, environmentId)
                .orElseThrow(() -> new ResourceNotFoundException("API key"));
    }

    private void recordHistory(AuthenticatedUser principal, ApiKey key, ApiKeyStatus from,
            ApiKeyStatus to, String action, String reason) {
        historyRepository.save(ApiKeyHistory.record(principal.organizationId(), key.getId(), from, to,
                action, principal.userId(), reason, Instant.now()));
    }

    private void enforceApiKeyLimit(AuthenticatedUser principal, UUID projectId, UUID environmentId,
            UUID excludedKeyId) {
        EnvironmentLimits limits = environmentLimitsRepository.findByEnvironmentId(environmentId)
                .orElse(null);
        if (limits == null) {
            return;
        }
        long active = apiKeyRepository.countByEnvironmentIdAndStatus(environmentId, ApiKeyStatus.ACTIVE);
        if (excludedKeyId != null) {
            ApiKey excludedKey = apiKeyRepository.findByIdAndOrganizationIdAndProjectIdAndEnvironmentId(
                    excludedKeyId, principal.organizationId(), projectId, environmentId).orElse(null);
            if (excludedKey != null && excludedKey.getStatus() == ApiKeyStatus.ACTIVE) {
                active--;
            }
        }
        if (active >= limits.getMaxApiKeys()) {
            throw new com.pesaguard.backend.common.exception.BusinessException(
                    HttpStatus.CONFLICT, "ENVIRONMENT_API_KEY_LIMIT_REACHED",
                    "This environment already has the maximum number of active API keys.");
        }
    }

    private Duration parseLifetime(String value) {
        try {
            Duration lifetime = Duration.parse(value);
            if (lifetime.isNegative() || lifetime.isZero() || lifetime.compareTo(MAX_LIFETIME) > 0) {
                throw new IllegalArgumentException("lifetime out of range");
            }
            return lifetime;
        } catch (RuntimeException exception) {
            throw new com.pesaguard.backend.common.exception.BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_EXPIRATION",
                    "expiresIn must be a positive ISO-8601 duration no longer than 366 days.");
        }
    }

    private void requireEnvironment(AuthenticatedUser principal, UUID projectId, UUID environmentId,
            boolean manageProject) {
        projectRepository.findByIdAndOrganizationId(projectId, principal.organizationId())
                .orElseThrow(() -> new ResourceNotFoundException("Project"));
        if (manageProject) projectAuthorization.requireProjectManage(principal, projectId);
        else projectAuthorization.requireProjectRead(principal, projectId);
        var environment = environmentRepository.findByIdAndOrganizationIdAndProjectId(
                        environmentId, principal.organizationId(), projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Environment"));
        environmentAccessPolicyService.requireAccess(principal, environment,
                manageProject ? EnvironmentPermission.ROTATE_CREDENTIALS : EnvironmentPermission.READ);
    }

    private ApiKeyView toView(ApiKey key, Instant now) {
        ApiKeyStatus effectiveStatus = key.effectiveStatus(now);
        String displayedStatus = effectiveStatus == ApiKeyStatus.ACTIVE
                && key.getExpiresAt() != null
                && !key.getExpiresAt().isAfter(now.plus(Duration.ofDays(30)))
                        ? "EXPIRING" : effectiveStatus.name();
        return new ApiKeyView(key.getId(), key.getProjectId(), key.getEnvironmentId(), key.getName(),
                key.getKeyPrefix(), key.scopeSet(), displayedStatus,
                key.getExpiresAt(), key.getLastUsedAt(), key.getRevokedAt(), key.getRequestCount(),
                key.getRotatedFromId(), ScopeCodec.decode(key.getIpAllowlist()), key.getLastUsedIp(),
                key.getLastUsedCountry(), key.getLastUsedDevice(),
                key.getCreatedAt());
    }
}
