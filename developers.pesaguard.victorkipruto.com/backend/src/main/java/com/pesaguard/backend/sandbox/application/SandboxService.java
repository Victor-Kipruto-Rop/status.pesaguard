package com.pesaguard.backend.sandbox.application;

import java.time.Clock;
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
import com.pesaguard.backend.common.exception.BusinessException;
import com.pesaguard.backend.common.exception.ResourceNotFoundException;
import com.pesaguard.backend.environment.domain.EnvironmentStatus;
import com.pesaguard.backend.environment.domain.EnvironmentPermission;
import com.pesaguard.backend.environment.application.EnvironmentAccessPolicyService;
import com.pesaguard.backend.environment.domain.EnvironmentType;
import com.pesaguard.backend.environment.domain.ProjectEnvironment;
import com.pesaguard.backend.environment.infrastructure.ProjectEnvironmentRepository;
import com.pesaguard.backend.project.application.ProjectAuthorization;
import com.pesaguard.backend.rbac.application.AuthorizationService;
import com.pesaguard.backend.rbac.domain.Permission;
import com.pesaguard.backend.sandbox.domain.Sandbox;
import com.pesaguard.backend.sandbox.domain.SandboxHistory;
import com.pesaguard.backend.sandbox.domain.SandboxIsolation;
import com.pesaguard.backend.sandbox.domain.SandboxIsolationGuard;
import com.pesaguard.backend.sandbox.domain.SandboxLimits;
import com.pesaguard.backend.sandbox.domain.SandboxStatus;
import com.pesaguard.backend.sandbox.infrastructure.SandboxHistoryRepository;
import com.pesaguard.backend.sandbox.infrastructure.SandboxIsolationGuardRepository;
import com.pesaguard.backend.sandbox.infrastructure.SandboxLimitsRepository;
import com.pesaguard.backend.sandbox.infrastructure.SandboxRepository;
import com.pesaguard.backend.security.principals.AuthenticatedUser;

/**
 * Sandbox lifecycle, and the isolation gate execution depends on.
 *
 * <p>{@link #requireExecutableIsolation} is the method every execution path must
 * call. It refuses unless the sandbox is active, unexpired, and backed by a
 * persisted isolation guard. That guard lookup is what makes the guarantee hold at
 * request time rather than only in the type system: execution can only proceed
 * from a sandbox that was pinned to a sandbox environment when it was created.
 */
@Service
public class SandboxService {

    private final SandboxRepository sandboxRepository;
    private final SandboxIsolationGuardRepository guardRepository;
    private final SandboxLimitsRepository limitsRepository;
    private final SandboxHistoryRepository historyRepository;
    private final ProjectEnvironmentRepository environmentRepository;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;
    private final Clock clock;
    private final EnvironmentAccessPolicyService environmentAccessPolicyService;
    private final ProjectAuthorization projectAuthorization;

    public SandboxService(
            SandboxRepository sandboxRepository,
            SandboxIsolationGuardRepository guardRepository,
            SandboxLimitsRepository limitsRepository,
            SandboxHistoryRepository historyRepository,
            ProjectEnvironmentRepository environmentRepository,
            AuthorizationService authorizationService,
            AuditService auditService,
            EnvironmentAccessPolicyService environmentAccessPolicyService,
            ProjectAuthorization projectAuthorization,
            Clock clock) {
        this.sandboxRepository = sandboxRepository;
        this.guardRepository = guardRepository;
        this.limitsRepository = limitsRepository;
        this.historyRepository = historyRepository;
        this.environmentRepository = environmentRepository;
        this.authorizationService = authorizationService;
        this.auditService = auditService;
        this.environmentAccessPolicyService = environmentAccessPolicyService;
        this.projectAuthorization = projectAuthorization;
        this.clock = clock;
    }

/**
     * Creates a sandbox pinned to an existing SANDBOX environment.
     *
     * <p>The environment must already be a sandbox. This service does not create
     * environments: a sandbox is pinned to one, and minting an environment here
     * would blur exactly the boundary this subsystem protects.
     */
    @Transactional
    public Sandbox create(AuthenticatedUser principal, UUID environmentId, String name,
            String description, Duration ttl) {
        authorizationService.requirePermission(principal, Permission.SANDBOX_CREATE);
        ProjectEnvironment environment = environmentRepository
                .findByIdAndOrganizationId(environmentId, principal.organizationId())
                .orElseThrow(() -> new ResourceNotFoundException("Environment"));
        projectAuthorization.requireProjectManage(principal, environment.getProjectId());
        environmentAccessPolicyService.requireAccess(principal, environment, EnvironmentPermission.WRITE);

        if (environment.getType() != EnvironmentType.SANDBOX) {
            // Refused here as well as in the isolation token. Two independent checks
            // on the write path, because this is the moment the mistake would be made
            // and it is the cheapest place to catch it.
            throw new BusinessException(HttpStatus.BAD_REQUEST, "ENVIRONMENT_NOT_SANDBOX",
                    "A sandbox must be created against a SANDBOX environment, but the "
                            + "requested environment is " + environment.getType() + ".");
        }
        if (environment.getStatus() != EnvironmentStatus.ACTIVE) {
            throw new BusinessException(HttpStatus.CONFLICT, "ENVIRONMENT_NOT_ACTIVE",
                    "The sandbox environment is not active.");
        }
        SandboxIsolation.requireNotProduction(environment.getType(), "sandbox creation");

        Instant now = clock.instant();
        Sandbox sandbox = sandboxRepository.saveAndFlush(Sandbox.create(principal.organizationId(),
                environment.getProjectId(), environment.getId(), name, description, ttl,
                principal.userId(), now));
        guardRepository.save(SandboxIsolationGuard.pin(sandbox));
        limitsRepository.save(SandboxLimits.defaults(sandbox.getId(), principal.organizationId()));
        historyRepository.save(SandboxHistory.record(sandbox, null, "sandbox.created",
                principal.userId(), null));
        auditService.append(principal.organizationId(), principal.userId(), "sandbox.created",
                "sandbox", sandbox.getId().toString(), RequestContext.currentRequestId(),
                Map.of("environmentId", environment.getId().toString()));
        return sandbox;
    }

    @Transactional
    public Sandbox activate(AuthenticatedUser principal, UUID sandboxId) {
        authorizationService.requirePermission(principal, Permission.SANDBOX_ACTIVATE);
        Sandbox sandbox = require(principal, sandboxId);
        requireEnvironmentAccess(principal, sandbox, EnvironmentPermission.WRITE);
        SandboxStatus from = sandbox.getStatus();
        sandbox.activate(clock.instant());
        record(principal, sandbox, from, "sandbox.activated", null);
        return sandbox;
    }

    @Transactional
    public Sandbox suspend(AuthenticatedUser principal, UUID sandboxId, String reason) {
        authorizationService.requirePermission(principal, Permission.SANDBOX_SUSPEND);
        Sandbox sandbox = require(principal, sandboxId);
        requireEnvironmentAccess(principal, sandbox, EnvironmentPermission.WRITE);
        SandboxStatus from = sandbox.getStatus();
        sandbox.suspend(clock.instant());
        record(principal, sandbox, from, "sandbox.suspended", reason);
        return sandbox;
    }

    @Transactional
    public Sandbox resume(AuthenticatedUser principal, UUID sandboxId) {
        authorizationService.requirePermission(principal, Permission.SANDBOX_ACTIVATE);
        Sandbox sandbox = require(principal, sandboxId);
        requireEnvironmentAccess(principal, sandbox, EnvironmentPermission.WRITE);
        SandboxStatus from = sandbox.getStatus();
        Instant now = clock.instant();
        try {
            sandbox.resume(now);
        } catch (IllegalStateException exception) {
            // resume() flips the status to EXPIRED before throwing, so persist that
            // rather than leaving the row claiming SUSPENDED.
            sandboxRepository.saveAndFlush(sandbox);
            throw new BusinessException(HttpStatus.CONFLICT, "SANDBOX_EXPIRED",
                    exception.getMessage());
        }
        record(principal, sandbox, from, "sandbox.resumed", null);
        return sandbox;
    }

    @Transactional
    public Sandbox delete(AuthenticatedUser principal, UUID sandboxId, String reason) {
        authorizationService.requirePermission(principal, Permission.SANDBOX_DELETE);
        Sandbox sandbox = require(principal, sandboxId);
        requireEnvironmentAccess(principal, sandbox, EnvironmentPermission.WRITE);
        SandboxStatus from = sandbox.getStatus();
        sandbox.delete(clock.instant());
        sandboxRepository.saveAndFlush(sandbox);
        record(principal, sandbox, from, "sandbox.deleted", reason);
        auditService.append(principal.organizationId(), principal.userId(), "sandbox.deleted",
                "sandbox", sandboxId.toString(), RequestContext.currentRequestId(), Map.of());
        return sandbox;
    }

    /**
     * Clears sandbox data and reseeds fixtures.
     *
     * <p>Only execution history is removed: it is the sandbox's own scratch space.
     * The lifecycle history is retained, because deleting a sandbox's record of
     * being suspended would defeat the audit trail.
     */
    @Transactional
    public Sandbox reset(AuthenticatedUser principal, UUID sandboxId, String reason) {
        authorizationService.requirePermission(principal, Permission.SANDBOX_RESET);
        Sandbox sandbox = require(principal, sandboxId);
        requireEnvironmentAccess(principal, sandbox, EnvironmentPermission.WRITE);
        sandbox.recordReset(clock.instant());
        sandboxRepository.saveAndFlush(sandbox);
        record(principal, sandbox, sandbox.getStatus(), "sandbox.reset", reason);
        return sandbox;
    }

/**
     * Resolves the isolation token an execution may proceed with, or refuses.
     *
     * <p>This is the point at which "sandbox never invokes production" is enforced
     * at request time. It checks three things, in order, and refuses on the first
     * failure:
     *
     * <ol>
     *   <li>the sandbox exists and belongs to the caller's organization;</li>
     *   <li>the sandbox is active and not past its expiry;</li>
     *   <li>an isolation guard was persisted when the sandbox was created.</li>
     * </ol>
     *
     * <p>Point 3 matters. Without it, a caller who could name any environment
     * could reach this method with a sandbox that was never pinned. The guard is
     * the persisted proof that this sandbox belongs to a sandbox environment, so
     * absence of the guard is absence of proof and the answer is no.
     */
    @Transactional(readOnly = true)
    public SandboxIsolation requireExecutableIsolation(AuthenticatedUser principal, UUID sandboxId) {
        Sandbox sandbox = require(principal, sandboxId);
        requireEnvironmentAccess(principal, sandbox, EnvironmentPermission.WRITE);
        Instant now = clock.instant();
        SandboxStatus effective = sandbox.effectiveStatus(now);
        if (!effective.allowsExecution()) {
            throw new BusinessException(HttpStatus.CONFLICT, "SANDBOX_NOT_EXECUTABLE",
                    "The sandbox is " + effective + " and cannot execute.");
        }
        return guardRepository.findBySandboxId(sandboxId)
                .map(SandboxIsolationGuard::toIsolation)
                .orElseThrow(() -> new BusinessException(HttpStatus.CONFLICT,
                        "SANDBOX_NOT_PINNED",
                        "The sandbox has no isolation guard and cannot execute."));
    }

    /** The sandbox's quota. Defaults are created with the sandbox, so absent is a fault. */
    @Transactional(readOnly = true)
    public SandboxLimits limits(AuthenticatedUser principal, UUID sandboxId) {
        Sandbox sandbox = require(principal, sandboxId);
        requireEnvironmentAccess(principal, sandbox, EnvironmentPermission.READ);
        return limitsRepository.findBySandboxId(sandboxId)
                .orElseThrow(() -> new BusinessException(HttpStatus.CONFLICT,
                        "SANDBOX_LIMITS_MISSING", "The sandbox has no configured limits."));
    }

    @Transactional(readOnly = true)
    public List<Sandbox> list(AuthenticatedUser principal, UUID projectId) {
        authorizationService.requirePermission(principal, Permission.SANDBOX_READ);
        if (projectId == null) {
            return sandboxRepository.findByOrganizationIdOrderByCreatedAtDesc(principal.organizationId())
                    .stream().filter(sandbox -> projectAuthorization.hasProjectRead(principal, sandbox.getProjectId()))
                    .filter(sandbox -> canReadEnvironment(principal, sandbox)).toList();
        }
        projectAuthorization.requireProjectRead(principal, projectId);
        return sandboxRepository.findByOrganizationIdAndProjectIdOrderByCreatedAtDesc(
                        principal.organizationId(), projectId).stream()
                .filter(sandbox -> canReadEnvironment(principal, sandbox)).toList();
    }

    @Transactional(readOnly = true)
    public Sandbox get(AuthenticatedUser principal, UUID sandboxId) {
        authorizationService.requirePermission(principal, Permission.SANDBOX_READ);
        Sandbox sandbox = require(principal, sandboxId);
        requireEnvironmentAccess(principal, sandbox, EnvironmentPermission.READ);
        return sandbox;
    }

    @Transactional
    public SandboxLimits updateLimits(AuthenticatedUser principal, UUID sandboxId, int requestsPerMinute,
            int burstRequests, int maxApiKeys, int maxCredentials, int maxWebhookEndpoints,
            int maxEventsPerMinute, int maxRequestBodyBytes, int maxResponseBodyBytes,
            int executionTimeoutMs, int maxHistoryEntries) {
        authorizationService.requirePermission(principal, Permission.SANDBOX_UPDATE);
        Sandbox sandbox = require(principal, sandboxId);
        requireEnvironmentAccess(principal, sandbox, EnvironmentPermission.WRITE);
        SandboxLimits limits = limitsRepository.findBySandboxId(sandboxId)
                .orElseGet(() -> SandboxLimits.defaults(sandboxId, principal.organizationId()));
        limits.update(requestsPerMinute, burstRequests, maxApiKeys, maxCredentials,
                maxWebhookEndpoints, maxEventsPerMinute, maxRequestBodyBytes, maxResponseBodyBytes,
                executionTimeoutMs, maxHistoryEntries, principal.userId());
        return limitsRepository.saveAndFlush(limits);
    }

    /**
     * Marks lapsed sandboxes expired.
     *
     * <p>A convenience sweep, not the enforcement. {@code effectiveStatus} already
     * makes an overdue sandbox inert, so a sweep that never runs costs nothing but
     * a stale status column.
     */
    @Transactional
    public int expireLapsed(Instant now) {
        int expired = 0;
        for (Sandbox sandbox : sandboxRepository
                .findByStatusAndExpiresAtBefore(SandboxStatus.ACTIVE, now)) {
            sandbox.expire(now);
            sandboxRepository.save(sandbox);
            historyRepository.save(SandboxHistory.record(sandbox, SandboxStatus.ACTIVE,
                    "sandbox.expired", null, "TTL elapsed"));
            expired++;
        }
        return expired;
    }

    private Sandbox require(AuthenticatedUser principal, UUID sandboxId) {
        return sandboxRepository.findByIdAndOrganizationId(sandboxId, principal.organizationId())
                .orElseThrow(() -> new ResourceNotFoundException("Sandbox"));
    }

    public void requireEnvironmentAccess(AuthenticatedUser principal, UUID sandboxId,
            EnvironmentPermission permission) {
        Sandbox sandbox = require(principal, sandboxId);
        requireEnvironmentAccess(principal, sandbox, permission);
    }

    private void requireEnvironmentAccess(AuthenticatedUser principal, Sandbox sandbox,
            EnvironmentPermission permission) {
        if (permission == EnvironmentPermission.READ) {
            projectAuthorization.requireProjectRead(principal, sandbox.getProjectId());
        } else {
            projectAuthorization.requireProjectManage(principal, sandbox.getProjectId());
        }
        ProjectEnvironment environment = environmentRepository
                .findByIdAndOrganizationIdAndProjectId(
                        sandbox.getEnvironmentId(), principal.organizationId(), sandbox.getProjectId())
                .orElseThrow(() -> new ResourceNotFoundException("Environment"));
        environmentAccessPolicyService.requireAccess(principal, environment, permission);
    }

    private boolean canReadEnvironment(AuthenticatedUser principal, Sandbox sandbox) {
        if (!projectAuthorization.hasProjectRead(principal, sandbox.getProjectId())) return false;
        return environmentRepository.findByIdAndOrganizationIdAndProjectId(
                        sandbox.getEnvironmentId(), principal.organizationId(), sandbox.getProjectId())
                .map(environment -> environmentAccessPolicyService.canAccess(
                        principal, environment, EnvironmentPermission.READ))
                .orElse(false);
    }

    private void record(AuthenticatedUser principal, Sandbox sandbox, SandboxStatus from,
            String action, String reason) {
        sandboxRepository.saveAndFlush(sandbox);
        historyRepository.save(SandboxHistory.record(sandbox, from, action, principal.userId(), reason));
        auditService.append(principal.organizationId(), principal.userId(), action, "sandbox",
                sandbox.getId().toString(), RequestContext.currentRequestId(), Map.of());
    }
}
