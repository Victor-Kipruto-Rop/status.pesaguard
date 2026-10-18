package com.pesaguard.backend.golive.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pesaguard.backend.audit.application.AuditService;
import com.pesaguard.backend.common.api.RequestContext;
import com.pesaguard.backend.common.exception.BusinessException;
import com.pesaguard.backend.common.exception.ResourceNotFoundException;
import com.pesaguard.backend.credentials.api.ApiKeyRepository;
import com.pesaguard.backend.environment.api.EnvironmentApiBaseUrls;
import com.pesaguard.backend.environment.application.EnvironmentAccessPolicyService;
import com.pesaguard.backend.environment.domain.EnvironmentPermission;
import com.pesaguard.backend.environment.domain.EnvironmentStatus;
import com.pesaguard.backend.environment.domain.EnvironmentType;
import com.pesaguard.backend.environment.domain.ProjectEnvironment;
import com.pesaguard.backend.environment.infrastructure.ProjectEnvironmentRepository;
import com.pesaguard.backend.golive.api.GoLiveCheckView;
import com.pesaguard.backend.golive.api.GoLiveLaunchView;
import com.pesaguard.backend.golive.api.GoLiveReadinessView;
import com.pesaguard.backend.golive.api.GoLiveVerificationJobView;
import com.pesaguard.backend.golive.domain.GoLiveLaunch;
import com.pesaguard.backend.golive.domain.GoLiveVerification;
import com.pesaguard.backend.golive.domain.GoLiveVerificationJob;
import com.pesaguard.backend.golive.infrastructure.GoLiveLaunchRepository;
import com.pesaguard.backend.golive.infrastructure.GoLiveVerificationRepository;
import com.pesaguard.backend.golive.infrastructure.GoLiveVerificationJobRepository;
import com.pesaguard.backend.member.domain.UserStatus;
import com.pesaguard.backend.member.infrastructure.MfaSecretRepository;
import com.pesaguard.backend.member.infrastructure.UserAccountRepository;
import com.pesaguard.backend.organization.domain.MembershipStatus;
import com.pesaguard.backend.organization.domain.OrganizationStatus;
import com.pesaguard.backend.organization.domain.OrganizationSecuritySettings;
import com.pesaguard.backend.organization.infrastructure.OrganizationMembershipRepository;
import com.pesaguard.backend.organization.infrastructure.OrganizationRepository;
import com.pesaguard.backend.organization.infrastructure.OrganizationSecuritySettingsRepository;
import com.pesaguard.backend.project.application.ProjectAuthorization;
import com.pesaguard.backend.project.domain.Project;
import com.pesaguard.backend.project.domain.ProjectStatus;
import com.pesaguard.backend.project.infrastructure.ProjectRepository;
import com.pesaguard.backend.rbac.application.AuthorizationService;
import com.pesaguard.backend.rbac.domain.Permission;
import com.pesaguard.backend.security.principals.AuthenticatedUser;
import com.pesaguard.backend.webhooks.domain.WebhookEndpoint;
import com.pesaguard.backend.webhooks.infrastructure.WebhookEndpointRepository;

@Service
public class GoLiveService {

    private static final int HISTORY_LIMIT = 30;
    private static final Duration VERIFICATION_MAX_AGE = Duration.ofMinutes(15);

    private final ProjectRepository projectRepository;
    private final ProjectEnvironmentRepository environmentRepository;
    private final ApiKeyRepository apiKeyRepository;
    private final WebhookEndpointRepository webhookEndpointRepository;
    private final GoLiveVerificationRepository verificationRepository;
    private final GoLiveVerificationJobRepository verificationJobRepository;
    private final GoLiveLaunchRepository launchRepository;
    private final UserAccountRepository userAccountRepository;
    private final OrganizationRepository organizationRepository;
    private final OrganizationMembershipRepository membershipRepository;
    private final OrganizationSecuritySettingsRepository securitySettingsRepository;
    private final MfaSecretRepository mfaSecretRepository;
    private final AuthorizationService authorizationService;
    private final ProjectAuthorization projectAuthorization;
    private final EnvironmentAccessPolicyService environmentAccessPolicyService;
    private final AuditService auditService;
    private final Clock clock;

    public GoLiveService(ProjectRepository projectRepository,
            ProjectEnvironmentRepository environmentRepository,
            ApiKeyRepository apiKeyRepository,
            WebhookEndpointRepository webhookEndpointRepository,
            GoLiveVerificationRepository verificationRepository,
            GoLiveVerificationJobRepository verificationJobRepository,
            GoLiveLaunchRepository launchRepository,
            UserAccountRepository userAccountRepository,
            OrganizationRepository organizationRepository,
            OrganizationMembershipRepository membershipRepository,
            OrganizationSecuritySettingsRepository securitySettingsRepository,
            MfaSecretRepository mfaSecretRepository,
            AuthorizationService authorizationService,
            ProjectAuthorization projectAuthorization,
            EnvironmentAccessPolicyService environmentAccessPolicyService,
            AuditService auditService,
            Clock clock) {
        this.projectRepository = projectRepository;
        this.environmentRepository = environmentRepository;
        this.apiKeyRepository = apiKeyRepository;
        this.webhookEndpointRepository = webhookEndpointRepository;
        this.verificationRepository = verificationRepository;
        this.verificationJobRepository = verificationJobRepository;
        this.launchRepository = launchRepository;
        this.userAccountRepository = userAccountRepository;
        this.organizationRepository = organizationRepository;
        this.membershipRepository = membershipRepository;
        this.securitySettingsRepository = securitySettingsRepository;
        this.mfaSecretRepository = mfaSecretRepository;
        this.authorizationService = authorizationService;
        this.projectAuthorization = projectAuthorization;
        this.environmentAccessPolicyService = environmentAccessPolicyService;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public GoLiveReadinessView readiness(AuthenticatedUser principal, UUID projectId, UUID environmentId) {
        Scope scope = requireScope(principal, projectId, environmentId, false);
        GoLiveReadinessView readiness = evaluate(principal, scope.project(), scope.environment(), null);
        GoLiveVerification latestVerification = verificationRepository
                .findByOrganizationIdAndProjectIdAndEnvironmentIdOrderByVerifiedAtDesc(
                        principal.organizationId(), projectId, environmentId, PageRequest.of(0, 1))
                .stream().findFirst().orElse(null);
        boolean recentReadyVerification = latestVerification != null
                && "READY".equals(latestVerification.getState())
                && !latestVerification.getVerifiedAt().isBefore(clock.instant().minus(VERIFICATION_MAX_AGE));
        boolean alreadyLaunched = launchRepository.existsByOrganizationIdAndProjectIdAndEnvironmentIdAndStatus(
                principal.organizationId(), projectId, environmentId, "LIVE");
        boolean previouslyRevoked = launchRepository.existsByOrganizationIdAndProjectIdAndEnvironmentIdAndStatus(
                principal.organizationId(), projectId, environmentId, "REVOKED");
        boolean live = alreadyLaunched;
        boolean canLaunch = !alreadyLaunched && readiness.canLaunch() && recentReadyVerification;
        String state = live ? "LIVE" : previouslyRevoked ? "REVOKED"
                : !readiness.canLaunch() ? readiness.state()
                : recentReadyVerification ? "READY" : latestVerification == null ? "NOT_STARTED" : "NOT_READY";
        return new GoLiveReadinessView(
                latestVerification == null ? null : latestVerification.getId(),
                readiness.projectId(), readiness.projectName(),
                readiness.projectStatus(), readiness.environmentId(), readiness.environmentName(),
                readiness.environmentStatus(), readiness.baseUrl(), state,
                readiness.readinessPercent(), readiness.passedChecks(), readiness.failedChecks(),
                readiness.blockingChecks(), readiness.warningChecks(), canLaunch,
                latestVerification == null ? null : latestVerification.getVerifiedAt(), readiness.checks());
    }

    @Transactional
    public GoLiveReadinessView verify(AuthenticatedUser principal, UUID projectId, UUID environmentId,
            String idempotencyKey) {
        authorizationService.requirePermission(principal, Permission.PRODUCTION_VERIFY);
        String normalizedKey = requireIdempotencyKey(idempotencyKey);
        Scope scope = requireScope(principal, projectId, environmentId, false);
        var previous = verificationRepository
                .findByOrganizationIdAndProjectIdAndEnvironmentIdAndIdempotencyKey(
                        principal.organizationId(), projectId, environmentId, normalizedKey);
        if (previous.isPresent()) {
            return view(scope.project(), scope.environment(), previous.get(), false);
        }
        GoLiveReadinessView evaluated = evaluate(principal, scope.project(), scope.environment(), null);
        GoLiveVerification verification = verificationRepository.saveAndFlush(GoLiveVerification.create(
                principal.organizationId(), projectId, environmentId, principal.userId(), normalizedKey,
                evaluated.state(), evaluated.readinessPercent(), evaluated.passedChecks(),
                evaluated.failedChecks(), evaluated.warningChecks(), evaluated.checks(), evaluated.verifiedAt()));
        auditService.append(principal.organizationId(), principal.userId(), "golive.verification.completed",
                "golive_verification", verification.getId().toString(), RequestContext.currentRequestId(),
                java.util.Map.of("projectId", projectId.toString(),
                        "environmentId", environmentId.toString(),
                        "state", verification.getState(),
                        "readinessPercent", verification.getReadinessPercent()));
        return view(scope.project(), scope.environment(), verification, false);
    }

    @Transactional
    public GoLiveVerificationJobView startVerification(AuthenticatedUser principal,
            UUID projectId, UUID environmentId, String idempotencyKey) {
        authorizationService.requirePermission(principal, Permission.PRODUCTION_VERIFY);
        String normalizedKey = requireIdempotencyKey(idempotencyKey);
        requireScope(principal, projectId, environmentId, false);
        var previous = verificationJobRepository
                .findByOrganizationIdAndProjectIdAndEnvironmentIdAndIdempotencyKey(
                        principal.organizationId(), projectId, environmentId, normalizedKey);
        if (previous.isPresent()) {
            return verificationJobView(previous.get());
        }

        GoLiveVerificationJob job = verificationJobRepository.saveAndFlush(GoLiveVerificationJob.queue(
                principal.organizationId(), projectId, environmentId, principal.userId(),
                normalizedKey, clock.instant()));
        auditService.append(principal.organizationId(), principal.userId(), "golive.verification.started",
                "golive_verification_job", job.getId().toString(), RequestContext.currentRequestId(),
                java.util.Map.of("projectId", projectId.toString(),
                        "environmentId", environmentId.toString()));
        processVerificationJob(job);
        return verificationJobView(job);
    }

    @Transactional(readOnly = true)
    public GoLiveVerificationJobView verificationJob(AuthenticatedUser principal,
            UUID projectId, UUID environmentId, UUID jobId) {
        requireScope(principal, projectId, environmentId, false);
        GoLiveVerificationJob job = verificationJobRepository
                .findByIdAndOrganizationIdAndProjectIdAndEnvironmentId(
                        jobId, principal.organizationId(), projectId, environmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Go-Live verification"));
        return verificationJobView(job);
    }

    @Transactional
    public void processNextVerificationJob() {
        var queuedJob = verificationJobRepository.findFirstByStatusOrderByQueuedAtAsc("QUEUED");
        queuedJob.ifPresent(this::processVerificationJob);
    }

    private void processVerificationJob(GoLiveVerificationJob job) {
        job.start(clock.instant());
        UUID requestId = UUID.randomUUID();

        var organization = organizationRepository.findById(job.getOrganizationId()).orElse(null);
        var membership = membershipRepository.findByOrganizationIdAndUserId(
                job.getOrganizationId(), job.getInitiatedBy())
                .filter(candidate -> candidate.getStatus() == MembershipStatus.ACTIVE)
                .orElse(null);
        var userAccount = userAccountRepository.findById(job.getInitiatedBy()).orElse(null);
        Project project = projectRepository.findByIdAndOrganizationId(job.getProjectId(), job.getOrganizationId())
                .orElse(null);
        ProjectEnvironment environment = project == null ? null
                : environmentRepository.findByIdAndOrganizationIdAndProjectId(
                        job.getEnvironmentId(), job.getOrganizationId(), job.getProjectId()).orElse(null);
        if (organization == null || organization.getStatus() != OrganizationStatus.ACTIVE
                || membership == null || membership.getStatus() != MembershipStatus.ACTIVE
                || userAccount == null || userAccount.getStatus() != UserStatus.ACTIVE
                || project == null || environment == null
                || environment.getType() != EnvironmentType.PRODUCTION) {
            failVerificationJob(job, "The initiator or Production environment is no longer available.",
                    "actor_or_scope_unavailable");
            return;
        }

        Set<String> authorities = Set.of("ROLE_" + membership.getRole().name());
        AuthenticatedUser initiatingUser = new AuthenticatedUser(job.getInitiatedBy(),
                job.getOrganizationId(), null, userAccount.getEmail(), userAccount.getDisplayName(),
                authorities, organization.getStatus(), false, Set.of(), false);
        if (!authorizationService.hasPermission(initiatingUser, Permission.PRODUCTION_VERIFY)) {
            failVerificationJob(job, "The initiator is no longer authorized to verify Production.",
                    "permission_changed");
            return;
        }
        GoLiveReadinessView evaluated = evaluate(initiatingUser, project, environment, null);
        GoLiveVerification verification = verificationRepository.saveAndFlush(GoLiveVerification.create(
                job.getOrganizationId(), job.getProjectId(), job.getEnvironmentId(),
                job.getInitiatedBy(), job.getIdempotencyKey(), evaluated.state(),
                evaluated.readinessPercent(), evaluated.passedChecks(), evaluated.failedChecks(),
                evaluated.warningChecks(), evaluated.checks(), evaluated.verifiedAt()));
        job.complete(verification.getId(), clock.instant());
        auditService.append(job.getOrganizationId(), job.getInitiatedBy(), "golive.verification.completed",
                "golive_verification", verification.getId().toString(), requestId,
                java.util.Map.of("projectId", job.getProjectId().toString(),
                        "environmentId", job.getEnvironmentId().toString(),
                        "state", verification.getState(),
                        "readinessPercent", verification.getReadinessPercent()));
    }

    private void failVerificationJob(GoLiveVerificationJob job, String message, String reason) {
        job.fail(message, clock.instant());
        auditService.append(job.getOrganizationId(), job.getInitiatedBy(), "golive.verification.failed",
                "golive_verification_job", job.getId().toString(), UUID.randomUUID(),
                java.util.Map.of("projectId", job.getProjectId().toString(),
                        "environmentId", job.getEnvironmentId().toString(),
                        "reason", reason));
    }

    @Transactional(readOnly = true)
    public List<GoLiveReadinessView> verifications(
            AuthenticatedUser principal, UUID projectId, UUID environmentId) {
        requireScope(principal, projectId, environmentId, false);
        return verificationRepository
                .findByOrganizationIdAndProjectIdAndEnvironmentIdOrderByVerifiedAtDesc(
                        principal.organizationId(), projectId, environmentId, PageRequest.of(0, HISTORY_LIMIT))
                .stream()
                .map(verification -> new GoLiveReadinessView(verification.getId(), projectId, null, null,
                        environmentId, null, null, null, verification.getState(),
                        verification.getReadinessPercent(), verification.getPassedChecks(),
                        verification.getFailedChecks(), (int) verification.getChecks().stream()
                                .filter(GoLiveCheckView::blocking).count(),
                        verification.getWarningChecks(),
                        !isBlocked(verification.getChecks()), verification.getVerifiedAt(),
                        verification.getChecks()))
                .toList();
    }

    @Transactional
    public GoLiveLaunchView launch(AuthenticatedUser principal, UUID projectId, UUID environmentId,
            String idempotencyKey) {
        String normalizedKey = requireIdempotencyKey(idempotencyKey);
        authorizationService.requirePermission(principal, Permission.PRODUCTION_LAUNCH);
        Scope scope = requireScope(principal, projectId, environmentId, true);
        return launchRepository.findByOrganizationIdAndProjectIdAndEnvironmentIdAndIdempotencyKey(
                        principal.organizationId(), projectId, environmentId, normalizedKey)
                .map(GoLiveService::toView)
                .orElseGet(() -> createLaunch(principal, scope, normalizedKey));
    }

    @Transactional(readOnly = true)
    public List<GoLiveLaunchView> launches(
            AuthenticatedUser principal, UUID projectId, UUID environmentId) {
        requireScope(principal, projectId, environmentId, false);
        return launchRepository
                .findByOrganizationIdAndProjectIdAndEnvironmentIdOrderByStartedAtDesc(
                        principal.organizationId(), projectId, environmentId, PageRequest.of(0, HISTORY_LIMIT))
                .stream().map(GoLiveService::toView).toList();
    }

    @Transactional
    public GoLiveReadinessView suspend(AuthenticatedUser principal, UUID projectId, UUID environmentId) {
        authorizationService.requirePermission(principal, Permission.PRODUCTION_SUSPEND);
        Scope scope = requireScope(principal, projectId, environmentId, true);
        requireLiveLaunch(principal, projectId, environmentId);
        if (scope.environment().getStatus() == EnvironmentStatus.SUSPENDED) {
            return evaluate(principal, scope.project(), scope.environment(), null);
        }
        if (scope.environment().getStatus() != EnvironmentStatus.ACTIVE) {
            throw new BusinessException(HttpStatus.CONFLICT, "GOLIVE_ENVIRONMENT_UNAVAILABLE",
                    "This Production environment cannot be suspended in its current state.");
        }
        scope.environment().suspend(clock.instant());
        environmentRepository.saveAndFlush(scope.environment());
        auditService.append(principal.organizationId(), principal.userId(), "golive.production.suspended",
                "project_environment", environmentId.toString(), RequestContext.currentRequestId(),
                java.util.Map.of("projectId", projectId.toString(), "environmentId", environmentId.toString()));
        return evaluate(principal, scope.project(), scope.environment(), null);
    }

    @Transactional
    public GoLiveReadinessView resume(AuthenticatedUser principal, UUID projectId, UUID environmentId) {
        authorizationService.requirePermission(principal, Permission.PRODUCTION_RESUME);
        Scope scope = requireScope(principal, projectId, environmentId, true);
        requireLiveLaunch(principal, projectId, environmentId);
        if (scope.environment().getStatus() != EnvironmentStatus.SUSPENDED) {
            throw new BusinessException(HttpStatus.CONFLICT, "GOLIVE_ENVIRONMENT_NOT_SUSPENDED",
                    "Only a suspended Production environment can be resumed.");
        }
        scope.environment().resume(clock.instant());
        GoLiveReadinessView readiness = evaluate(principal, scope.project(), scope.environment(), null);
        if (!readiness.canLaunch()) {
            throw new BusinessException(HttpStatus.CONFLICT, "GOLIVE_RESUME_BLOCKED",
                    "Production cannot resume until all blocking readiness checks pass.");
        }
        environmentRepository.saveAndFlush(scope.environment());
        auditService.append(principal.organizationId(), principal.userId(), "golive.production.resumed",
                "project_environment", environmentId.toString(), RequestContext.currentRequestId(),
                java.util.Map.of("projectId", projectId.toString(), "environmentId", environmentId.toString()));
        return readiness;
    }

    @Transactional
    public GoLiveLaunchView revoke(AuthenticatedUser principal, UUID projectId, UUID environmentId) {
        authorizationService.requirePermission(principal, Permission.PRODUCTION_SUSPEND);
        requireScope(principal, projectId, environmentId, true);
        GoLiveLaunch launch = launchRepository
                .findFirstByOrganizationIdAndProjectIdAndEnvironmentIdAndStatusOrderByStartedAtDesc(
                        principal.organizationId(), projectId, environmentId, "LIVE")
                .orElseThrow(() -> new BusinessException(HttpStatus.CONFLICT, "GOLIVE_NOT_LIVE",
                        "This Production environment has no active Go-Live launch."));
        launch.revoke(clock.instant());
        GoLiveLaunch saved = launchRepository.saveAndFlush(launch);
        auditService.append(principal.organizationId(), principal.userId(), "golive.production.revoked",
                "golive_launch", saved.getId().toString(), RequestContext.currentRequestId(),
                java.util.Map.of("projectId", projectId.toString(), "environmentId", environmentId.toString()));
        return toView(saved);
    }

    private void requireLiveLaunch(AuthenticatedUser principal, UUID projectId, UUID environmentId) {
        if (!launchRepository.existsByOrganizationIdAndProjectIdAndEnvironmentIdAndStatus(
                principal.organizationId(), projectId, environmentId, "LIVE")) {
            throw new BusinessException(HttpStatus.CONFLICT, "GOLIVE_NOT_LIVE",
                    "This Production environment has no active Go-Live launch.");
        }
    }

    private GoLiveLaunchView createLaunch(
            AuthenticatedUser principal, Scope scope, String idempotencyKey) {
        if (launchRepository.existsByOrganizationIdAndProjectIdAndEnvironmentIdAndStatus(
                principal.organizationId(), scope.project().getId(), scope.environment().getId(), "LIVE")) {
            throw new BusinessException(HttpStatus.CONFLICT, "GOLIVE_ALREADY_LIVE",
                    "This production environment has already been launched.");
        }
        GoLiveVerification verification = verificationRepository
                .findByOrganizationIdAndProjectIdAndEnvironmentIdOrderByVerifiedAtDesc(
                        principal.organizationId(), scope.project().getId(), scope.environment().getId(),
                        PageRequest.of(0, 1))
                .stream().findFirst()
                .orElseThrow(() -> new BusinessException(HttpStatus.CONFLICT,
                        "GOLIVE_VERIFICATION_REQUIRED",
                        "Run a Production verification before launching."));
        if (verification.getVerifiedAt().isBefore(clock.instant().minus(VERIFICATION_MAX_AGE))) {
            throw new BusinessException(HttpStatus.CONFLICT, "GOLIVE_VERIFICATION_EXPIRED",
                    "The latest Production verification is more than 15 minutes old. Run it again before launching.");
        }
        if (!"READY".equals(verification.getState())) {
            throw new BusinessException(HttpStatus.CONFLICT, "GOLIVE_VERIFICATION_BLOCKED",
                    "The latest Production verification contains blocking readiness checks.");
        }
        GoLiveReadinessView readiness = evaluate(principal, scope.project(), scope.environment(), null);
        Instant now = clock.instant();
        UUID requestId = RequestContext.currentRequestId();
        String requestRef = requestId == null ? null : requestId.toString();
        GoLiveLaunch launch = readiness.canLaunch()
                ? GoLiveLaunch.completed(principal.organizationId(), scope.project().getId(),
                        scope.environment().getId(), principal.userId(), verification.getId(),
                        idempotencyKey, now, now, requestRef)
                : GoLiveLaunch.failed(principal.organizationId(), scope.project().getId(),
                        scope.environment().getId(), principal.userId(), verification.getId(),
                        idempotencyKey, "Required Production readiness checks did not pass.", now, now, requestRef);
        launch = launchRepository.saveAndFlush(launch);
        auditService.append(principal.organizationId(), principal.userId(), "golive.launch.started",
                "golive_launch", launch.getId().toString(), requestId,
                java.util.Map.of("projectId", scope.project().getId().toString(),
                        "environmentId", scope.environment().getId().toString(),
                        "verificationId", verification.getId().toString()));
        String eventType = readiness.canLaunch() ? "golive.launch.completed" : "golive.launch.failed";
        auditService.append(principal.organizationId(), principal.userId(), eventType,
                "golive_launch", launch.getId().toString(), requestId,
                java.util.Map.of("projectId", scope.project().getId().toString(),
                        "environmentId", scope.environment().getId().toString(),
                        "verificationId", verification.getId().toString(),
                        "status", launch.getStatus()));
        return toView(launch);
    }

    private Scope requireScope(AuthenticatedUser principal, UUID projectId, UUID environmentId,
            boolean launching) {
        authorizationService.requirePermission(principal,
                launching ? Permission.ENVIRONMENT_UPDATE : Permission.ENVIRONMENT_READ);
        if (!launching) {
            authorizationService.requirePermission(principal, Permission.PRODUCTION_VIEW);
        }
        if (launching) {
            projectAuthorization.requireProjectManage(principal, projectId);
        } else {
            projectAuthorization.requireProjectRead(principal, projectId);
        }
        Project project = projectRepository.findByIdAndOrganizationId(projectId, principal.organizationId())
                .orElseThrow(() -> new ResourceNotFoundException("Project"));
        var environmentResult = launching
                ? environmentRepository.findByIdAndOrganizationIdAndProjectIdForUpdate(
                        environmentId, principal.organizationId(), projectId)
                : environmentRepository.findByIdAndOrganizationIdAndProjectId(
                        environmentId, principal.organizationId(), projectId);
        ProjectEnvironment environment = environmentResult
                .orElseThrow(() -> new ResourceNotFoundException("Environment"));
        if (launching) {
            environmentAccessPolicyService.requireAccess(principal, environment, EnvironmentPermission.DEPLOY);
        } else {
            environmentAccessPolicyService.requireAccess(principal, environment, EnvironmentPermission.READ);
        }
        if (environment.getType() != EnvironmentType.PRODUCTION) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "GOLIVE_REQUIRES_PRODUCTION",
                    "Go-Live checks and launch operations are only available for a Production environment.");
        }
        return new Scope(project, environment);
    }

    private GoLiveReadinessView evaluate(AuthenticatedUser principal, Project project,
            ProjectEnvironment environment, UUID verificationId) {
        Instant now = clock.instant();
        List<GoLiveCheckView> checks = new ArrayList<>();
        var account = userAccountRepository.findById(principal.userId()).orElse(null);
        boolean accountActive = account != null && account.getStatus() == UserStatus.ACTIVE;
        add(checks, "account-active", "Developer account is active", "ACCOUNT", "BLOCKER",
                accountActive, "The account initiating Go-Live must be active.",
                account == null ? "Account unavailable" : account.getStatus().name(),
                "Restore the account or contact your organization administrator.", now);
        boolean emailVerified = account != null && account.isEmailVerified();
        add(checks, "account-email-verified", "Email address is verified", "ACCOUNT", "BLOCKER",
                emailVerified, "The developer account email address must be verified before launch.",
                emailVerified ? "Verified" : "Not verified",
                "Verify the email address in Account settings before launching.", now);
        boolean organizationActive = organizationRepository.findById(principal.organizationId())
                .map(organization -> organization.getStatus() == OrganizationStatus.ACTIVE)
                .orElse(false);
        add(checks, "organization-active", "Organization is active", "ORGANIZATION", "BLOCKER",
                organizationActive, "The selected organization must be active.",
                organizationActive ? "Active" : "Unavailable",
                "Restore the organization before launching Production.", now);
        OrganizationSecuritySettings securitySettings = securitySettingsRepository
                .findById(principal.organizationId()).orElse(null);
        boolean administrator = principal.authorities().stream().map(authority ->
                authority.startsWith("ROLE_") ? authority.substring(5) : authority)
                .anyMatch(role -> role.equals("OWNER") || role.equals("ADMIN"));
        boolean mfaRequired = securitySettings != null
                && (securitySettings.isMfaRequired()
                        || administrator && securitySettings.isMfaRequiredForAdmins());
        boolean mfaEnrolled = mfaSecretRepository
                .findByUserIdAndRevokedAtIsNullAndConfirmedAtIsNotNull(principal.userId()).isPresent();
        add(checks, "account-mfa-policy", "Organization MFA requirement", "SECURITY", "CRITICAL",
                !mfaRequired || mfaEnrolled,
                "A confirmed authenticator is required when organization security policy enforces MFA.",
                !mfaRequired ? "MFA is not required by current organization policy."
                        : mfaEnrolled ? "Confirmed authenticator enrolled." : "Required authenticator not enrolled.",
                "Enroll and confirm an authenticator in Security settings.", now);

        boolean projectActive = project.getStatus() == ProjectStatus.ACTIVE;
        add(checks, "project-active", "Project is active", "PROJECT", "BLOCKER",
                projectActive, "The project must be active and not archived or deactivated.",
                project.getStatus().name(), "Restore or activate the project before launching.", now);
        boolean environmentActive = environment.getStatus() == EnvironmentStatus.ACTIVE;
        add(checks, "production-environment-active", "Production environment is active", "ENVIRONMENT",
                "BLOCKER", environmentActive, "The selected Production environment must be active.",
                environment.getStatus().name(), "Resume the Production environment before launching.", now);
        boolean correctProject = environment.getProjectId().equals(project.getId())
                && environment.getOrganizationId().equals(principal.organizationId());
        add(checks, "environment-scope", "Environment belongs to this project", "ENVIRONMENT", "BLOCKER",
                correctProject, "The Production environment must belong to the selected project and organization.",
                correctProject ? "Verified" : "Scope mismatch",
                "Select a Production environment in the active project.", now);
        String baseUrl = EnvironmentApiBaseUrls.forType(environment.getType());
        add(checks, "production-base-url", "Production API base URL", "ENVIRONMENT", "BLOCKER",
                !baseUrl.isBlank(), "A server-owned Production API URL is configured.",
                baseUrl.isBlank() ? "Missing" : baseUrl,
                "Contact PesaGuard support if the Production API URL is unavailable.", now);

        long activeKeys = apiKeyRepository.countUsableProductionKeys(environment.getId(), now);
        add(checks, "production-credentials", "Active Production API key", "CREDENTIALS", "BLOCKER",
                activeKeys > 0, "At least one active, unexpired API key must be bound to Production.",
                activeKeys > 0 ? activeKeys + " active key(s); secret values were not inspected." : "No active key",
                "Create and securely store a key bound to this Production environment.", now);

        List<WebhookEndpoint> endpoints = webhookEndpointRepository
                .findByOrganizationIdAndProjectIdAndEnvironmentIdAndStatusNotOrderByCreatedAtDesc(
                        principal.organizationId(), project.getId(), environment.getId(),
                        "DELETED", PageRequest.of(0, 100));
        List<WebhookEndpoint> activeEndpoints = endpoints.stream()
                .filter(endpoint -> "ACTIVE".equals(endpoint.getStatus())).toList();
        if (activeEndpoints.isEmpty()) {
            checks.add(new GoLiveCheckView("webhook-endpoints", "Production webhooks", "WEBHOOKS",
                    "WARNING", "WARNING", false,
                    "No active Production webhook endpoint is configured.",
                    "No active endpoints", "Add a Production webhook if your integration needs event delivery.",
                    "/?page=webhooks", now));
        } else {
            boolean secureEndpoints = activeEndpoints.stream().allMatch(endpoint ->
                    endpoint.getUrl().startsWith("https://")
                            && endpoint.getSigningSecretCiphertext() != null
                            && !endpoint.getSigningSecretCiphertext().isBlank());
            add(checks, "webhook-security", "Webhook transport and signing", "WEBHOOKS", "CRITICAL",
                    secureEndpoints, "Every active Production webhook must use HTTPS and have signing configured.",
                    activeEndpoints.size() + " active endpoint(s) inspected; signing secrets were not exposed.",
                    "Use HTTPS and configure webhook signing for every active endpoint.", now);
        }
        boolean launched = launchRepository.existsByOrganizationIdAndProjectIdAndEnvironmentIdAndStatus(
                principal.organizationId(), project.getId(), environment.getId(), "LIVE");
        int passed = (int) checks.stream().filter(check -> "PASSED".equals(check.status())).count();
        int failed = (int) checks.stream().filter(check -> "FAILED".equals(check.status())).count();
        int blocking = (int) checks.stream().filter(GoLiveCheckView::blocking).count();
        int warnings = (int) checks.stream().filter(check -> "WARNING".equals(check.status())).count();
        int percent = checks.isEmpty() ? 0 : (int) Math.round(passed * 100.0 / checks.size());
        String state = launched ? environmentActive ? "LIVE" : "SUSPENDED"
                : blocking > 0 ? "NOT_READY" : "READY";
        return new GoLiveReadinessView(verificationId, project.getId(), project.getName(),
                project.getStatus().name(), environment.getId(), environment.getName(),
                environment.getStatus().name(), baseUrl, state, percent, passed, failed, blocking,
                warnings, blocking == 0 && projectActive && environmentActive, now, List.copyOf(checks));
    }

    private static void add(List<GoLiveCheckView> checks, String id, String name, String category,
            String severity, boolean passed, String description, String result,
            String remediation, Instant checkedAt) {
        checks.add(new GoLiveCheckView(id, name, category, severity,
                passed ? "PASSED" : "FAILED", !passed && !"WARNING".equals(severity),
                description, result, passed ? null : remediation, routeFor(category, id), checkedAt));
    }

    private static boolean isBlocked(List<GoLiveCheckView> checks) {
        return checks.stream().anyMatch(GoLiveCheckView::blocking);
    }

    private static String requireIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()
                || idempotencyKey.length() > 128 || idempotencyKey.trim().length() < 8) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_INVALID",
                    "Provide an idempotency key between 8 and 128 characters.");
        }
        return idempotencyKey.trim();
    }

    private static String routeFor(String category, String checkId) {
        if ("account-active".equals(checkId) || "production-base-url".equals(checkId)) {
            return "/?page=support-hub";
        }
        if ("account-mfa-policy".equals(checkId)) {
            return "/?page=account-settings";
        }
        return switch (category) {
            case "ACCOUNT" -> "/?page=account-settings";
            case "ORGANIZATION" -> "/?page=organizations";
            case "PROJECT" -> "/?page=projects";
            case "ENVIRONMENT" -> "/?page=environments";
            case "CREDENTIALS" -> "/?page=api-keys";
            case "WEBHOOKS" -> "/?page=webhooks";
            case "SECURITY" -> "/?page=security-center";
            default -> null;
        };
    }

    private static GoLiveReadinessView view(Project project, ProjectEnvironment environment,
            GoLiveVerification verification, boolean launched) {
        return new GoLiveReadinessView(verification.getId(), project.getId(), project.getName(),
                project.getStatus().name(), environment.getId(), environment.getName(),
                environment.getStatus().name(), EnvironmentApiBaseUrls.forType(environment.getType()),
                launched ? "LIVE" : verification.getState(), verification.getReadinessPercent(),
                verification.getPassedChecks(), verification.getFailedChecks(),
                (int) verification.getChecks().stream().filter(GoLiveCheckView::blocking).count(),
                verification.getWarningChecks(), !isBlocked(verification.getChecks()),
                verification.getVerifiedAt(), verification.getChecks());
    }

    private static GoLiveLaunchView toView(GoLiveLaunch launch) {
        return new GoLiveLaunchView(launch.getId(), launch.getProjectId(), launch.getEnvironmentId(),
                launch.getVerificationId(), launch.getStatus(), launch.getFailureReason(),
                launch.getStartedAt(), launch.getCompletedAt(), launch.getRequestId());
    }

    private GoLiveVerificationJobView verificationJobView(GoLiveVerificationJob job) {
        GoLiveReadinessView result = null;
        if (job.getVerificationId() != null) {
            GoLiveVerification verification = verificationRepository
                    .findByIdAndOrganizationIdAndProjectIdAndEnvironmentId(job.getVerificationId(),
                            job.getOrganizationId(), job.getProjectId(), job.getEnvironmentId())
                    .orElseThrow(() -> new ResourceNotFoundException("Go-Live verification"));
            Project project = projectRepository.findByIdAndOrganizationId(job.getProjectId(), job.getOrganizationId())
                    .orElseThrow(() -> new ResourceNotFoundException("Project"));
            ProjectEnvironment environment = environmentRepository
                    .findByIdAndOrganizationIdAndProjectId(job.getEnvironmentId(),
                            job.getOrganizationId(), job.getProjectId())
                    .orElseThrow(() -> new ResourceNotFoundException("Environment"));
            result = view(project, environment, verification, false);
        }
        return new GoLiveVerificationJobView(job.getId(), job.getStatus(), job.getVerificationId(),
                job.getFailureReason(), job.getQueuedAt(), job.getStartedAt(), job.getCompletedAt(), result);
    }

    private record Scope(Project project, ProjectEnvironment environment) {
    }
}
