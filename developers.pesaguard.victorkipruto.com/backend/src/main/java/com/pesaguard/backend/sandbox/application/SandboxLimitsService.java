package com.pesaguard.backend.sandbox.application;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pesaguard.backend.rbac.application.AuthorizationService;
import com.pesaguard.backend.rbac.domain.Permission;
import com.pesaguard.backend.environment.domain.EnvironmentPermission;
import com.pesaguard.backend.sandbox.api.UpdateSandboxLimitsRequest;
import com.pesaguard.backend.sandbox.domain.SandboxLimits;
import com.pesaguard.backend.security.principals.AuthenticatedUser;

/**
 * Sandbox quota management.
 *
 * <p>Separate from {@link SandboxService} so that the lifecycle service has a
 * single responsibility and so the permission required to spend a sandbox's budget
 * is not the same one that can delete it.
 */
@Service
public class SandboxLimitsService {

    private final SandboxService sandboxService;
    private final com.pesaguard.backend.sandbox.infrastructure.SandboxLimitsRepository limitsRepository;
    private final AuthorizationService authorizationService;

    public SandboxLimitsService(
            SandboxService sandboxService,
            com.pesaguard.backend.sandbox.infrastructure.SandboxLimitsRepository limitsRepository,
            AuthorizationService authorizationService) {
        this.sandboxService = sandboxService;
        this.limitsRepository = limitsRepository;
        this.authorizationService = authorizationService;
    }

    @Transactional
    public SandboxLimits update(AuthenticatedUser principal, UUID sandboxId,
            UpdateSandboxLimitsRequest request) {
        authorizationService.requirePermission(principal, Permission.SANDBOX_UPDATE);
        // Resolved for its tenant check.
        sandboxService.get(principal, sandboxId);
        sandboxService.requireEnvironmentAccess(principal, sandboxId, EnvironmentPermission.WRITE);
        SandboxLimits limits = sandboxService.limits(principal, sandboxId);
        limits.update(request.requestsPerMinute(), request.burstRequests(), request.maxApiKeys(),
                request.maxCredentials(), request.maxWebhookEndpoints(),
                request.maxEventsPerMinute(), request.maxRequestBodyBytes(),
                request.maxResponseBodyBytes(), request.executionTimeoutMs(),
                request.maxHistoryEntries(), principal.userId());
        return limitsRepository.saveAndFlush(limits);
    }
}