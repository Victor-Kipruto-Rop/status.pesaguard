package com.pesaguard.backend.environment.application;

import java.time.Clock;
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
import com.pesaguard.backend.common.exception.ResourceConflictException;
import com.pesaguard.backend.common.exception.ResourceNotFoundException;
import com.pesaguard.backend.environment.api.CreateEnvironmentRequest;
import com.pesaguard.backend.environment.api.EnvironmentHistoryView;
import com.pesaguard.backend.environment.api.EnvironmentLimitsView;
import com.pesaguard.backend.environment.api.EnvironmentView;
import com.pesaguard.backend.environment.api.PromoteEnvironmentRequest;
import com.pesaguard.backend.environment.api.UpdateEnvironmentConfigurationRequest;
import com.pesaguard.backend.environment.api.UpdateEnvironmentLimitsRequest;
import com.pesaguard.backend.environment.domain.EnvironmentHistory;
import com.pesaguard.backend.environment.domain.EnvironmentPermission;
import com.pesaguard.backend.environment.domain.EnvironmentLimits;
import com.pesaguard.backend.environment.domain.EnvironmentStatus;
import com.pesaguard.backend.environment.domain.EnvironmentType;
import com.pesaguard.backend.environment.domain.ProjectEnvironment;
import com.pesaguard.backend.environment.infrastructure.EnvironmentHistoryRepository;
import com.pesaguard.backend.environment.infrastructure.EnvironmentLimitsRepository;
import com.pesaguard.backend.environment.infrastructure.ProjectEnvironmentRepository;
import com.pesaguard.backend.integration.application.IntegrationProvisioningService;
import com.pesaguard.backend.project.application.ProjectAuthorization;
import com.pesaguard.backend.project.domain.Project;
import com.pesaguard.backend.project.domain.ProjectStatus;
import com.pesaguard.backend.project.infrastructure.ProjectRepository;
import com.pesaguard.backend.rbac.application.AuthorizationService;
import com.pesaguard.backend.rbac.domain.Permission;
import com.pesaguard.backend.security.principals.AuthenticatedUser;

@Service
public class EnvironmentService {

    private final ProjectEnvironmentRepository environmentRepository;
    private final ProjectRepository projectRepository;
    private final EnvironmentLimitsRepository limitsRepository;
    private final EnvironmentHistoryRepository historyRepository;
    private final AuthorizationService authorizationService;
    private final ProjectAuthorization projectAuthorization;
    private final EnvironmentAccessPolicyService accessPolicyService;
    private final EnvironmentConfigurationValidator configurationValidator;
    private final IntegrationProvisioningService integrationProvisioning;
    private final AuditService auditService;
    private final Clock clock;

    public EnvironmentService(
            ProjectEnvironmentRepository environmentRepository,
            ProjectRepository projectRepository,
            EnvironmentLimitsRepository limitsRepository,
            EnvironmentHistoryRepository historyRepository,
            AuthorizationService authorizationService,
            ProjectAuthorization projectAuthorization,
            EnvironmentAccessPolicyService accessPolicyService,
            EnvironmentConfigurationValidator configurationValidator,
            IntegrationProvisioningService integrationProvisioning,
            AuditService auditService,
            Clock clock) {
        this.environmentRepository = environmentRepository;
        this.projectRepository = projectRepository;
        this.limitsRepository = limitsRepository;
        this.historyRepository = historyRepository;
        this.authorizationService = authorizationService;
        this.projectAuthorization = projectAuthorization;
        this.accessPolicyService = accessPolicyService;
        this.configurationValidator = configurationValidator;
        this.integrationProvisioning = integrationProvisioning;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional
    public EnvironmentView create(AuthenticatedUser principal, UUID projectId, CreateEnvironmentRequest request) {
        authorizationService.requirePermission(principal, Permission.ENVIRONMENT_CREATE);
        Project project = requireProject(principal, projectId);
        projectAuthorization.requireProjectManage(principal, projectId);
        if (project.getStatus() != ProjectStatus.ACTIVE) {
            throw new ResourceConflictException("PROJECT_NOT_ACTIVE",
                    "Only an active project can receive environments.");
        }
        String name = request.name().trim();
        if (environmentRepository.existsByProjectIdAndName(projectId, name)) {
            throw new ResourceConflictException("ENVIRONMENT_NAME_EXISTS",
                    "An environment with this name already exists.");
        }
        if (environmentRepository.existsByProjectIdAndType(projectId, request.type())) {
            throw new ResourceConflictException("ENVIRONMENT_TIER_EXISTS",
                    "This project already has an environment at the " + request.type() + " tier.");
        }
        Instant now = clock.instant();
        ProjectEnvironment environment = environmentRepository.saveAndFlush(ProjectEnvironment.create(
                principal.organizationId(), projectId, name, request.type(), principal.userId(), now));
        integrationProvisioning.ensureDefault(principal.organizationId(), projectId,
                environment.getId(), environment.getType(), now);
        limitsRepository.saveAndFlush(EnvironmentLimits.defaults(
                environment.getId(), principal.organizationId(), projectId, now));
        historyRepository.save(EnvironmentHistory.record(principal.organizationId(), projectId,
                environment.getId(), null, request.type(), EnvironmentStatus.ACTIVE, request.type(),
                "environment.created", principal.userId(), null, now));
        auditService.append(principal.organizationId(), principal.userId(), "environment.created",
                "environment", environment.getId().toString(), RequestContext.currentRequestId(),
                Map.of("projectId", projectId.toString(), "type", environment.getType().name()));
        return toView(environment);
    }

    @Transactional(readOnly = true)
    public List<EnvironmentView> list(AuthenticatedUser principal, UUID projectId) {
        authorizationService.requirePermission(principal, Permission.ENVIRONMENT_READ);
        requireProject(principal, projectId);
        return environmentRepository.findByOrganizationIdAndProjectIdOrderByCreatedAtAsc(
                        principal.organizationId(), projectId)
                .stream().filter(environment -> accessPolicyService.canAccess(
                        principal, environment, EnvironmentPermission.READ))
                .map(this::toView).toList();
    }

    @Transactional(readOnly = true)
    public EnvironmentView get(AuthenticatedUser principal, UUID projectId, UUID environmentId) {
        authorizationService.requirePermission(principal, Permission.ENVIRONMENT_READ);
        ProjectEnvironment environment = require(principal, projectId, environmentId);
        accessPolicyService.requireAccess(principal, environment, EnvironmentPermission.READ);
        return toView(environment);
    }

    @Transactional
    public EnvironmentView updateConfiguration(AuthenticatedUser principal, UUID projectId, UUID environmentId,
            UpdateEnvironmentConfigurationRequest request) {
        authorizationService.requirePermission(principal, Permission.ENVIRONMENT_UPDATE);
        projectAuthorization.requireProjectManage(principal, projectId);
        ProjectEnvironment environment = environmentRepository.findByIdAndOrganizationIdAndProjectIdForUpdate(
                environmentId, principal.organizationId(), projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Environment"));
        requireWritable(environment);
        accessPolicyService.requireAccess(principal, environment, EnvironmentPermission.WRITE);
        configurationValidator.validate(request.configuration(), environment.getType());
        environment.updateConfiguration(request.configuration());
        environmentRepository.saveAndFlush(environment);
        history(principal, projectId, environment, "environment.configuration_updated");
        return toView(environment);
    }

    @Transactional
    public EnvironmentView suspend(AuthenticatedUser principal, UUID projectId, UUID environmentId) {
        authorizationService.requirePermission(principal, Permission.ENVIRONMENT_UPDATE);
        projectAuthorization.requireProjectManage(principal, projectId);
        ProjectEnvironment environment = require(principal, projectId, environmentId);
        accessPolicyService.requireAccess(principal, environment, EnvironmentPermission.WRITE);
        requireWritable(environment);
        environment.suspend(clock.instant());
        environmentRepository.saveAndFlush(environment);
        history(principal, projectId, environment, "environment.suspended");
        return toView(environment);
    }

    @Transactional
    public EnvironmentView resume(AuthenticatedUser principal, UUID projectId, UUID environmentId) {
        authorizationService.requirePermission(principal, Permission.ENVIRONMENT_UPDATE);
        projectAuthorization.requireProjectManage(principal, projectId);
        ProjectEnvironment environment = require(principal, projectId, environmentId);
        accessPolicyService.requireAccess(principal, environment, EnvironmentPermission.WRITE);
        environment.resume(clock.instant());
        environmentRepository.saveAndFlush(environment);
        history(principal, projectId, environment, "environment.resumed");
        return toView(environment);
    }

    @Transactional
    public EnvironmentView deactivate(AuthenticatedUser principal, UUID projectId, UUID environmentId) {
        authorizationService.requirePermission(principal, Permission.ENVIRONMENT_UPDATE);
        projectAuthorization.requireProjectManage(principal, projectId);
        ProjectEnvironment environment = require(principal, projectId, environmentId);
        accessPolicyService.requireAccess(principal, environment, EnvironmentPermission.WRITE);
        environment.deactivate(clock.instant());
        environmentRepository.saveAndFlush(environment);
        history(principal, projectId, environment, "environment.deactivated");
        return toView(environment);
    }

    /** Promotes an environment exactly one tier; the target tier must be free. */
    @Transactional
    public EnvironmentView promote(AuthenticatedUser principal, UUID projectId, UUID environmentId,
            PromoteEnvironmentRequest request) {
        authorizationService.requirePermission(principal, Permission.ENVIRONMENT_UPDATE);
        projectAuthorization.requireProjectManage(principal, projectId);
        ProjectEnvironment environment = require(principal, projectId, environmentId);
        accessPolicyService.requireAccess(principal, environment, EnvironmentPermission.DEPLOY);
        requireWritable(environment);
        EnvironmentType target = request.targetType();
        if (!environment.getType().canPromoteTo(target)) {
            throw new BusinessException(HttpStatus.CONFLICT, "INVALID_ENVIRONMENT_PROMOTION",
                    "An environment may only be promoted one tier at a time.");
        }
        if (environmentRepository.existsByProjectIdAndType(projectId, target)) {
            throw new ResourceConflictException("ENVIRONMENT_TIER_EXISTS",
                    "This project already has an environment at the " + target + " tier.");
        }
        environment.promoteTo(target, clock.instant());
        environmentRepository.saveAndFlush(environment);
        historyRepository.save(EnvironmentHistory.record(principal.organizationId(), projectId,
                environmentId, environment.getStatus(), target, environment.getStatus(), target,
                "environment.promoted", principal.userId(), request.reason(), clock.instant()));
        auditService.append(principal.organizationId(), principal.userId(), "environment.promoted",
                "environment", environmentId.toString(), RequestContext.currentRequestId(),
                Map.of("targetType", target.name()));
        return toView(environment);
    }

    @Transactional
    public List<EnvironmentHistoryView> history(AuthenticatedUser principal, UUID projectId, UUID environmentId) {
        authorizationService.requirePermission(principal, Permission.ENVIRONMENT_READ);
        ProjectEnvironment environment = require(principal, projectId, environmentId);
        accessPolicyService.requireAccess(principal, environment, EnvironmentPermission.READ);
        return historyRepository.findByEnvironmentIdOrderByCreatedAtDesc(environmentId).stream()
                .map(this::toHistoryView).toList();
    }

    @Transactional(readOnly = true)
    public EnvironmentLimitsView limits(AuthenticatedUser principal, UUID projectId, UUID environmentId) {
        authorizationService.requirePermission(principal, Permission.ENVIRONMENT_READ);
        requireProject(principal, projectId);
        ProjectEnvironment environment = require(principal, projectId, environmentId);
        accessPolicyService.requireAccess(principal, environment, EnvironmentPermission.READ);
        return limitsRepository.findByEnvironmentId(environmentId).map(EnvironmentLimitsView::from)
                .orElseThrow(() -> new ResourceNotFoundException("Environment limits"));
    }

    @Transactional
    public EnvironmentLimitsView updateLimits(AuthenticatedUser principal, UUID projectId, UUID environmentId,
            UpdateEnvironmentLimitsRequest request) {
        authorizationService.requirePermission(principal, Permission.ENVIRONMENT_UPDATE);
        projectAuthorization.requireProjectManage(principal, projectId);
        ProjectEnvironment environment = environmentRepository.findByIdAndOrganizationIdAndProjectIdForUpdate(
                environmentId, principal.organizationId(), projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Environment"));
        requireWritable(environment);
        accessPolicyService.requireAccess(principal, environment, EnvironmentPermission.WRITE);
        EnvironmentLimits limits = limitsRepository.findByEnvironmentId(environmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Environment limits"));
        limits.update(request.requestsPerMinute(), request.burstRequests(), request.maxApiKeys(),
                request.maxCredentials(), request.credentialRotationIntervalMinutes(),
                principal.userId(), clock.instant());
        limitsRepository.saveAndFlush(limits);
        auditService.append(principal.organizationId(), principal.userId(), "environment.limits.updated",
                "environment_limits", environmentId.toString(), RequestContext.currentRequestId(),
                Map.of("requestsPerMinute", request.requestsPerMinute(), "burstRequests", request.burstRequests(),
                        "maxApiKeys", request.maxApiKeys(), "maxCredentials", request.maxCredentials(),
                        "credentialRotationIntervalMinutes", request.credentialRotationIntervalMinutes()));
        return EnvironmentLimitsView.from(limits);
    }

    private void requireWritable(ProjectEnvironment environment) {
        if (!environment.getStatus().acceptsChanges()) {
            throw new BusinessException(HttpStatus.CONFLICT, "ENVIRONMENT_NOT_ACTIVE",
                    "A " + environment.getStatus().name().toLowerCase(java.util.Locale.ROOT)
                            + " environment cannot be modified.");
        }
    }

    private Project requireProject(AuthenticatedUser principal, UUID projectId) {
        Project project = projectRepository.findByIdAndOrganizationId(projectId, principal.organizationId())
                .orElseThrow(() -> new ResourceNotFoundException("Project"));
        projectAuthorization.requireProjectRead(principal, projectId);
        return project;
    }

    private ProjectEnvironment require(AuthenticatedUser principal, UUID projectId, UUID environmentId) {
        return environmentRepository.findByIdAndOrganizationIdAndProjectId(
                        environmentId, principal.organizationId(), projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Environment"));
    }

    private void history(AuthenticatedUser principal, UUID projectId, ProjectEnvironment environment, String action) {
        historyRepository.save(EnvironmentHistory.record(principal.organizationId(), projectId,
                environment.getId(), environment.getStatus(), environment.getType(), action,
                principal.userId(), null, clock.instant()));
        auditService.append(principal.organizationId(), principal.userId(), action, "environment",
                environment.getId().toString(), RequestContext.currentRequestId(),
                Map.of("projectId", projectId.toString()));
    }

    private EnvironmentView toView(ProjectEnvironment environment) {
        return new EnvironmentView(environment.getId(), environment.getProjectId(), environment.getName(),
                environment.getType(), environment.getStatus(), environment.getConfiguration(),
                environment.getStatusChangedAt(), environment.getCreatedAt(), environment.getUpdatedAt());
    }

    private EnvironmentHistoryView toHistoryView(EnvironmentHistory entry) {
        return new EnvironmentHistoryView(entry.getId(), entry.getEnvironmentId(), entry.getAction(),
                entry.getFromType(), entry.getToType(), entry.getFromStatus(), entry.getToStatus(),
                entry.getActorUserId(), entry.getReason(), entry.getCreatedAt());
    }
}
