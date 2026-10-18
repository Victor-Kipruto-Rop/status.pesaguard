package com.pesaguard.backend.environment.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pesaguard.backend.common.exception.BusinessException;
import com.pesaguard.backend.audit.application.AuditService;
import com.pesaguard.backend.common.api.RequestContext;
import com.pesaguard.backend.environment.api.EnvironmentCredentialView;
import com.pesaguard.backend.environment.domain.EnvironmentCredential;
import com.pesaguard.backend.environment.domain.EnvironmentPermission;
import com.pesaguard.backend.environment.domain.EnvironmentCredentialStatus;
import com.pesaguard.backend.environment.domain.EnvironmentCredentialType;
import com.pesaguard.backend.environment.domain.ProjectEnvironment;
import com.pesaguard.backend.environment.infrastructure.EnvironmentCredentialRepository;
import com.pesaguard.backend.environment.infrastructure.EnvironmentLimitsRepository;
import com.pesaguard.backend.environment.infrastructure.ProjectEnvironmentRepository;
import com.pesaguard.backend.security.credentials.CredentialCryptoService;
import com.pesaguard.backend.security.credentials.SecretEncryptionService;
import com.pesaguard.backend.project.application.ProjectAuthorization;
import com.pesaguard.backend.rbac.application.AuthorizationService;
import com.pesaguard.backend.rbac.domain.Permission;
import com.pesaguard.backend.security.principals.AuthenticatedUser;

/**
 * Environment-scoped secrets.
 *
 * <p>The rule this service exists to enforce is tier isolation: a secret belongs
 * to exactly one environment and is never presented to another. Concretely, a
 * production secret cannot be registered in sandbox, because a sandbox credential
 * is by definition reachable by everyone with sandbox access and by whatever
 * third-party sandbox that environment talks to.
 *
 * <p>Reuse is detected with a keyed fingerprint, not by comparing secrets. The
 * same secret yields the same fingerprint in every tier, so a collision is
 * literal reuse.
 */
@Service
public class EnvironmentCredentialService {

    private final ProjectEnvironmentRepository environmentRepository;
    private final EnvironmentCredentialRepository credentialRepository;
    private final SecretKey credentialKey;
    private final Clock clock;
    private final AuthorizationService authorizationService;
    private final ProjectAuthorization projectAuthorization;
    private final AuditService auditService;
    private final SecretEncryptionService secretEncryptionService;
    private final EnvironmentLimitsRepository limitsRepository;
    private final EnvironmentAccessPolicyService accessPolicyService;

    public EnvironmentCredentialService(
            ProjectEnvironmentRepository environmentRepository,
            EnvironmentCredentialRepository credentialRepository,
            @Qualifier("credentialHmacKey") SecretKey credentialKey,
            AuthorizationService authorizationService,
            ProjectAuthorization projectAuthorization,
            AuditService auditService,
            SecretEncryptionService secretEncryptionService,
            EnvironmentLimitsRepository limitsRepository,
            EnvironmentAccessPolicyService accessPolicyService,
            Clock clock) {
        this.environmentRepository = environmentRepository;
        this.credentialRepository = credentialRepository;
        this.credentialKey = credentialKey;
        this.authorizationService = authorizationService;
        this.projectAuthorization = projectAuthorization;
        this.auditService = auditService;
        this.secretEncryptionService = secretEncryptionService;
        this.limitsRepository = limitsRepository;
        this.accessPolicyService = accessPolicyService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<EnvironmentCredentialView> list(AuthenticatedUser principal,
            UUID projectId, UUID environmentId) {
        authorizationService.requirePermission(principal, Permission.ENVIRONMENT_READ);
        projectAuthorization.requireProjectRead(principal, projectId);
        accessPolicyService.requireAccess(principal,
                requireEnvironment(principal.organizationId(), projectId, environmentId),
                EnvironmentPermission.READ);
        return credentialRepository.findByEnvironmentIdOrderByCreatedAtDesc(environmentId).stream()
                .map(EnvironmentCredentialView::from).toList();
    }

    @Transactional
    public EnvironmentCredentialView create(AuthenticatedUser principal, UUID projectId, UUID environmentId,
            String name, EnvironmentCredentialType type, String secret) {
        authorizationService.requirePermission(principal, Permission.ENVIRONMENT_UPDATE);
        projectAuthorization.requireProjectManage(principal, projectId);
        accessPolicyService.requireAccess(principal,
                requireEnvironment(principal.organizationId(), projectId, environmentId),
                EnvironmentPermission.ROTATE_CREDENTIALS);
        EnvironmentCredential credential = storeForActor(principal.organizationId(), projectId, environmentId,
                name, type, secret, principal.userId());
        auditService.append(principal.organizationId(), principal.userId(), "environment.credential.created",
                "environment_credential", credential.getId().toString(), RequestContext.currentRequestId(),
                java.util.Map.of("environmentId", environmentId.toString(),
                        "type", credential.getCredentialType().name(), "version", credential.getVersion()));
        return EnvironmentCredentialView.from(credential);
    }

    @Transactional
    public boolean revoke(AuthenticatedUser principal, UUID projectId, UUID environmentId, UUID credentialId) {
        authorizationService.requirePermission(principal, Permission.ENVIRONMENT_UPDATE);
        projectAuthorization.requireProjectManage(principal, projectId);
        accessPolicyService.requireAccess(principal,
                requireEnvironment(principal.organizationId(), projectId, environmentId),
                EnvironmentPermission.ROTATE_CREDENTIALS);
        boolean changed = credentialRepository
                .findByIdAndEnvironmentIdAndProjectIdAndOrganizationId(
                        credentialId, environmentId, projectId, principal.organizationId())
                .map(credential -> {
                    boolean wasActive = credential.getStatus() == EnvironmentCredentialStatus.ACTIVE;
                    credential.revoke(clock.instant());
                    if (wasActive) credentialRepository.saveAndFlush(credential);
                    return wasActive;
                }).orElse(false);
        if (changed) {
            auditService.append(principal.organizationId(), principal.userId(), "environment.credential.revoked",
                    "environment_credential", credentialId.toString(), RequestContext.currentRequestId(),
                    java.util.Map.of("environmentId", environmentId.toString()));
        }
        return changed;
    }

    /**
     * Registers a secret against one environment.
     *
     * @throws BusinessException when the secret is already in use in another tier
     */
    @Transactional
    EnvironmentCredential store(UUID organizationId, UUID projectId, UUID environmentId,
            String name, EnvironmentCredentialType type, String secret) {
        ProjectEnvironment environment = requireEnvironment(organizationId, projectId, environmentId);
        return store(environment, organizationId, projectId, environmentId, name, type, secret,
                environment.getCreatedBy());
    }

    private EnvironmentCredential storeForActor(UUID organizationId, UUID projectId, UUID environmentId,
            String name, EnvironmentCredentialType type, String secret, UUID actorId) {
        ProjectEnvironment environment = environmentRepository.findByIdAndOrganizationIdAndProjectIdForUpdate(
                environmentId, organizationId, projectId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND,
                        "ENVIRONMENT_NOT_FOUND", "The environment was not found."));
        return store(environment, organizationId, projectId, environmentId, name, type, secret, actorId);
    }

    private EnvironmentCredential store(ProjectEnvironment environment, UUID organizationId, UUID projectId,
            UUID environmentId, String name, EnvironmentCredentialType type, String secret, UUID actorId) {
        if (environment.getStatus() != com.pesaguard.backend.environment.domain.EnvironmentStatus.ACTIVE) {
            throw new BusinessException(HttpStatus.CONFLICT, "ENVIRONMENT_NOT_ACTIVE",
                    "Credentials can only be changed in an active environment.");
        }
        if (name == null || name.isBlank() || type == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_CREDENTIAL_METADATA",
                    "A credential name and type are required.");
        }
        long activeCredentials = credentialRepository.countByEnvironmentIdAndStatus(
                environmentId, EnvironmentCredentialStatus.ACTIVE);
        int maximum = limitsRepository.findByEnvironmentId(environmentId)
                .map(com.pesaguard.backend.environment.domain.EnvironmentLimits::getMaxCredentials)
                .orElse(20);
        if (activeCredentials >= maximum) {
            throw new BusinessException(HttpStatus.CONFLICT, "ENVIRONMENT_CREDENTIAL_LIMIT_REACHED",
                    "The environment has reached its active credential limit.");
        }
        name = name.trim();
        if (secret == null || secret.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "CREDENTIAL_SECRET_REQUIRED", "A credential secret is required.");
        }

        String fingerprint = fingerprint(secret);
        if (credentialRepository.existsByFingerprintAndProjectIdAndEnvironmentIdNot(
                fingerprint, projectId, environmentId)) {
            // Deliberately does not name the tier holding it. Telling the caller
            // which environment already owns a secret is information they do not
            // need in order to comply, and may not be entitled to.
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "CREDENTIAL_ALREADY_IN_USE",
                    "This secret is already registered in another environment for this project.");
        }

        int nextVersion = credentialRepository
                .findByEnvironmentIdAndNameOrderByVersionDesc(environmentId, name)
                .stream()
                .mapToInt(EnvironmentCredential::getVersion)
                .max()
                .orElse(0) + 1;

        // Neither the secret nor the hash is logged. The name is enough to correlate.
        EnvironmentCredential stored = EnvironmentCredential.create(
                organizationId, projectId, environment.getId(), name, type,
                CredentialCryptoService.hmacSha256(credentialKey, "env-secret:" + secret),
                secretEncryptionService.encrypt(secret),
                fingerprint,
                nextVersion,
                actorId);
        try {
            return credentialRepository.saveAndFlush(stored);
        } catch (DataIntegrityViolationException violation) {
            // The (project_id, fingerprint) unique index lost a race with a
            // concurrent request that registered this same secret elsewhere, or
            // caught a repeat of the same secret under a second name in this
            // environment. Reported as the same conflict as the check above, so
            // a caller cannot tell the two apart and retry into the same wall.
            throw new BusinessException(HttpStatus.CONFLICT,
                    "CREDENTIAL_ALREADY_IN_USE",
                    "This secret is already registered in another environment for this project.");
        }
    }

    /** Revokes a secret. Idempotent: revoking twice keeps the first timestamp. */
    @Transactional
    boolean revoke(UUID organizationId, UUID projectId, UUID environmentId, UUID credentialId) {
        return credentialRepository
                .findByIdAndEnvironmentIdAndProjectIdAndOrganizationId(
                        credentialId, environmentId, projectId, organizationId)
                .map(credential -> {
                    boolean changed = credential.getStatus() == EnvironmentCredentialStatus.ACTIVE;
                    credential.revoke(clock.instant());
                    return changed;
                })
                .orElse(false);
    }

    private ProjectEnvironment requireEnvironment(UUID organizationId, UUID projectId, UUID environmentId) {
        return environmentRepository.findByIdAndOrganizationIdAndProjectId(environmentId, organizationId, projectId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND,
                        "ENVIRONMENT_NOT_FOUND", "The environment was not found."));
    }

    /**
     * Keyed fingerprint of a secret.
     *
     * <p>Keyed so an attacker with a read-only copy of the table cannot confirm a
     * guessed secret by recomputing a bare SHA-256 of it.
     */
    private String fingerprint(String secret) {
        return CredentialCryptoService.hmacSha256(credentialKey, "env-fingerprint:" + secret);
    }
}
