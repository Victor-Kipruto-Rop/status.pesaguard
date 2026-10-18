package com.pesaguard.backend.onboarding.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pesaguard.backend.analytics.infrastructure.ApiRequestEventRepository;
import com.pesaguard.backend.audit.application.AuditService;
import com.pesaguard.backend.common.api.RequestContext;
import com.pesaguard.backend.common.exception.BusinessException;
import com.pesaguard.backend.credentials.api.ApiKeyRepository;
import com.pesaguard.backend.credentials.api.ApiKeyStatus;
import com.pesaguard.backend.environment.domain.EnvironmentType;
import com.pesaguard.backend.environment.infrastructure.ProjectEnvironmentRepository;
import com.pesaguard.backend.integration.domain.IntegrationStatus;
import com.pesaguard.backend.integration.infrastructure.IntegrationRepository;
import com.pesaguard.backend.member.infrastructure.MfaSecretRepository;
import com.pesaguard.backend.onboarding.api.DeveloperOnboardingProgressView;
import com.pesaguard.backend.onboarding.api.DeveloperOnboardingStatus;
import com.pesaguard.backend.onboarding.api.DeveloperOnboardingStepView;
import com.pesaguard.backend.onboarding.api.DeveloperOnboardingWorkflowView;
import com.pesaguard.backend.onboarding.domain.DeveloperOnboardingProgress;
import com.pesaguard.backend.onboarding.domain.DeveloperOnboardingStepKey;
import com.pesaguard.backend.onboarding.domain.DeveloperOnboardingStepRecord;
import com.pesaguard.backend.onboarding.domain.DeveloperOnboardingStepStatus;
import com.pesaguard.backend.onboarding.infrastructure.DeveloperOnboardingProgressRepository;
import com.pesaguard.backend.onboarding.infrastructure.DeveloperOnboardingStepRepository;
import com.pesaguard.backend.organization.domain.MembershipStatus;
import com.pesaguard.backend.organization.infrastructure.OrganizationMembershipRepository;
import com.pesaguard.backend.rbac.domain.ProductionAccessStatus;
import com.pesaguard.backend.rbac.infrastructure.ProductionAccessRequestRepository;
import com.pesaguard.backend.security.principals.AuthenticatedUser;
import com.pesaguard.backend.webhooks.infrastructure.WebhookEndpointRepository;

@Service
public class DeveloperOnboardingWorkflowService {

    private record Definition(
            DeveloperOnboardingStepKey key,
            boolean required,
            boolean conditional,
            List<DeveloperOnboardingStepKey> prerequisites) {
    }

    private static final List<Definition> STEPS = List.of(
            new Definition(DeveloperOnboardingStepKey.ACCOUNT, true, false, List.of()),
            new Definition(DeveloperOnboardingStepKey.EMAIL_VERIFICATION, true, false,
                    List.of(DeveloperOnboardingStepKey.ACCOUNT)),
            new Definition(DeveloperOnboardingStepKey.SECURITY, true, false,
                    List.of(DeveloperOnboardingStepKey.EMAIL_VERIFICATION)),
            new Definition(DeveloperOnboardingStepKey.PROFILE, true, false,
                    List.of(DeveloperOnboardingStepKey.SECURITY)),
            new Definition(DeveloperOnboardingStepKey.ORGANIZATION, true, false,
                    List.of(DeveloperOnboardingStepKey.PROFILE)),
            new Definition(DeveloperOnboardingStepKey.PROJECT, true, false,
                    List.of(DeveloperOnboardingStepKey.ORGANIZATION)),
            new Definition(DeveloperOnboardingStepKey.SANDBOX, true, false,
                    List.of(DeveloperOnboardingStepKey.PROJECT)),
            new Definition(DeveloperOnboardingStepKey.API_KEY, true, false,
                    List.of(DeveloperOnboardingStepKey.SANDBOX)),
            new Definition(DeveloperOnboardingStepKey.FIRST_API_REQUEST, true, false,
                    List.of(DeveloperOnboardingStepKey.API_KEY)),
            new Definition(DeveloperOnboardingStepKey.API_EXPLORER, false, false,
                    List.of(DeveloperOnboardingStepKey.FIRST_API_REQUEST)),
            new Definition(DeveloperOnboardingStepKey.INTEGRATION, false, false,
                    List.of(DeveloperOnboardingStepKey.PROJECT)),
            new Definition(DeveloperOnboardingStepKey.WEBHOOK, false, false,
                    List.of(DeveloperOnboardingStepKey.PROJECT)),
            new Definition(DeveloperOnboardingStepKey.TEAM, false, false,
                    List.of(DeveloperOnboardingStepKey.ORGANIZATION)),
            new Definition(DeveloperOnboardingStepKey.DOCUMENTATION, false, false,
                    List.of(DeveloperOnboardingStepKey.PROJECT)),
            new Definition(DeveloperOnboardingStepKey.PRODUCTION_READINESS, true, false,
                    List.of(DeveloperOnboardingStepKey.FIRST_API_REQUEST)),
            new Definition(DeveloperOnboardingStepKey.PRODUCTION_REQUEST, false, true,
                    List.of(DeveloperOnboardingStepKey.PRODUCTION_READINESS)),
            new Definition(DeveloperOnboardingStepKey.PRODUCTION_APPROVAL, false, true,
                    List.of(DeveloperOnboardingStepKey.PRODUCTION_REQUEST)),
            new Definition(DeveloperOnboardingStepKey.PRODUCTION_CREDENTIALS, false, true,
                    List.of(DeveloperOnboardingStepKey.PRODUCTION_APPROVAL)),
            new Definition(DeveloperOnboardingStepKey.COMPLETION, true, false,
                    List.of(DeveloperOnboardingStepKey.PRODUCTION_READINESS)));

    private static final Set<DeveloperOnboardingStepKey> OPTIONAL_STEPS = Set.of(
            DeveloperOnboardingStepKey.API_EXPLORER,
            DeveloperOnboardingStepKey.INTEGRATION,
            DeveloperOnboardingStepKey.WEBHOOK,
            DeveloperOnboardingStepKey.TEAM,
            DeveloperOnboardingStepKey.DOCUMENTATION,
            DeveloperOnboardingStepKey.PRODUCTION_REQUEST,
            DeveloperOnboardingStepKey.PRODUCTION_APPROVAL,
            DeveloperOnboardingStepKey.PRODUCTION_CREDENTIALS);

    private final DeveloperOnboardingStatusService statusService;
    private final DeveloperOnboardingProgressRepository progressRepository;
    private final DeveloperOnboardingStepRepository stepRepository;
    private final ProjectEnvironmentRepository environmentRepository;
    private final ApiKeyRepository apiKeyRepository;
    private final ApiRequestEventRepository requestEventRepository;
    private final MfaSecretRepository mfaSecretRepository;
    private final IntegrationRepository integrationRepository;
    private final WebhookEndpointRepository webhookRepository;
    private final OrganizationMembershipRepository membershipRepository;
    private final ProductionAccessRequestRepository productionAccessRepository;
    private final AuditService auditService;
    private final Clock clock;

    public DeveloperOnboardingWorkflowService(
            DeveloperOnboardingStatusService statusService,
            DeveloperOnboardingProgressRepository progressRepository,
            DeveloperOnboardingStepRepository stepRepository,
            ProjectEnvironmentRepository environmentRepository,
            ApiKeyRepository apiKeyRepository,
            ApiRequestEventRepository requestEventRepository,
            MfaSecretRepository mfaSecretRepository,
            IntegrationRepository integrationRepository,
            WebhookEndpointRepository webhookRepository,
            OrganizationMembershipRepository membershipRepository,
            ProductionAccessRequestRepository productionAccessRepository,
            AuditService auditService,
            Clock clock) {
        this.statusService = statusService;
        this.progressRepository = progressRepository;
        this.stepRepository = stepRepository;
        this.environmentRepository = environmentRepository;
        this.apiKeyRepository = apiKeyRepository;
        this.requestEventRepository = requestEventRepository;
        this.mfaSecretRepository = mfaSecretRepository;
        this.integrationRepository = integrationRepository;
        this.webhookRepository = webhookRepository;
        this.membershipRepository = membershipRepository;
        this.productionAccessRepository = productionAccessRepository;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public DeveloperOnboardingWorkflowView workflowFor(AuthenticatedUser principal) {
        DeveloperOnboardingStatus status = statusService.statusFor(principal);
        DeveloperOnboardingProgress progress = progressRepository.findById(principal.userId()).orElse(null);
        Map<DeveloperOnboardingStepKey, DeveloperOnboardingStepRecord> records = new EnumMap<>(
                DeveloperOnboardingStepKey.class);
        stepRepository.findByUserIdOrderByCreatedAtAsc(principal.userId()).forEach(
                record -> DeveloperOnboardingStepKey.parse(record.getStepKey())
                        .ifPresent(key -> records.put(key, record)));

        List<DeveloperOnboardingStepView> views = new ArrayList<>(STEPS.size());
        int completed = 0;
        int total = 0;
        String currentStep = DeveloperOnboardingStepKey.COMPLETION.name();
        boolean currentStepFound = false;
        for (Definition definition : STEPS) {
            DeveloperOnboardingStepKey key = definition.key();
            boolean applicable = isApplicable(key, principal, status);
            boolean satisfied = isSatisfied(key, principal, status, progress);
            DeveloperOnboardingStepRecord record = records.get(key);
            DeveloperOnboardingStepStatus stepStatus = resolveStatus(
                    definition, applicable, satisfied, record, principal, status, progress);
            String blockedReason = blockedReason(
                    definition, stepStatus, principal, status, progress);

            if (definition.required() || (definition.conditional() && applicable)) {
                total++;
                if (stepStatus == DeveloperOnboardingStepStatus.COMPLETED) {
                    completed++;
                } else if (!currentStepFound) {
                    currentStep = key.name();
                    currentStepFound = true;
                }
            }
            views.add(new DeveloperOnboardingStepView(
                    key.name(), stepStatus.name().toLowerCase(java.util.Locale.ROOT),
                    definition.required(), definition.conditional(), blockedReason,
                    record == null ? null : record.getStartedAt(),
                    stepStatus == DeveloperOnboardingStepStatus.COMPLETED
                            ? satisfiedAt(record, progress, key) : null,
                    record == null ? null : record.getSkippedAt()));
        }

        int percentage = total == 0 ? 0 : (int) Math.round(completed * 100.0 / total);
        String workflowStatus = progress != null && progress.isCompleted() ? "completed"
                : progress != null && progress.isSkipped() ? "skipped"
                        : progress == null ? "not_started" : "in_progress";
        return new DeveloperOnboardingWorkflowView(workflowStatus, currentStep,
                new DeveloperOnboardingProgressView(completed, total, percentage), views,
                progress == null ? 0 : progress.getVersion());
    }

    @Transactional
    public DeveloperOnboardingWorkflowView startStep(AuthenticatedUser principal, String requestedStep) {
        Definition definition = definition(requestedStep);
        DeveloperOnboardingStatus status = statusService.statusFor(principal);
        requirePrerequisites(definition, principal, status, null);
        DeveloperOnboardingProgress progress = progressFor(principal);
        Instant now = clock.instant();
        progress.update(legacyStep(definition.key()), null, now);
        progressRepository.save(progress);

        DeveloperOnboardingStepRecord record = stepRepository
                .findByUserIdAndStepKey(principal.userId(), definition.key().name())
                .orElseGet(() -> DeveloperOnboardingStepRecord.start(principal.userId(),
                        definition.key().name(), definition.required(), definition.conditional(), now));
        record.start(now);
        stepRepository.save(record);
        auditStep(principal, "developer_onboarding.step_started", definition.key());
        return workflowFor(principal);
    }

    @Transactional
    public DeveloperOnboardingWorkflowView completeStep(AuthenticatedUser principal, String requestedStep) {
        Definition definition = definition(requestedStep);
        DeveloperOnboardingStepKey key = definition.key();
        if (key == DeveloperOnboardingStepKey.COMPLETION) {
            throw blocked("Complete onboarding through the completion action.");
        }

        DeveloperOnboardingStatus status = statusService.statusFor(principal);
        DeveloperOnboardingProgress progress = progressRepository.findById(principal.userId()).orElse(null);
        requirePrerequisites(definition, principal, status, progress);
        boolean userAcknowledgement = key == DeveloperOnboardingStepKey.API_EXPLORER
                || key == DeveloperOnboardingStepKey.DOCUMENTATION;
        if (!userAcknowledgement && !isSatisfied(key, principal, status, progress)) {
            throw blocked(reasonFor(key));
        }

        Instant now = clock.instant();
        DeveloperOnboardingStepRecord record = stepRepository
                .findByUserIdAndStepKey(principal.userId(), key.name())
                .orElseGet(() -> DeveloperOnboardingStepRecord.start(principal.userId(), key.name(),
                        definition.required(), definition.conditional(), now));
        record.complete(now);
        stepRepository.save(record);
        auditStep(principal, "developer_onboarding.step_completed", key);
        return workflowFor(principal);
    }

    @Transactional
    public DeveloperOnboardingWorkflowView skipStep(AuthenticatedUser principal, String requestedStep) {
        Definition definition = definition(requestedStep);
        if (definition.required() || !OPTIONAL_STEPS.contains(definition.key())) {
            throw new BusinessException(HttpStatus.CONFLICT, "ONBOARDING_STEP_REQUIRED",
                    "This onboarding step is required and cannot be skipped.");
        }
        DeveloperOnboardingStatus status = statusService.statusFor(principal);
        requirePrerequisites(definition, principal, status, null);
        Instant now = clock.instant();
        DeveloperOnboardingProgress progress = progressFor(principal);
        progress.update(legacyStep(definition.key()), null, now);
        progressRepository.save(progress);

        DeveloperOnboardingStepRecord record = stepRepository
                .findByUserIdAndStepKey(principal.userId(), definition.key().name())
                .orElseGet(() -> DeveloperOnboardingStepRecord.start(principal.userId(),
                        definition.key().name(), false, definition.conditional(), now));
        record.skip(now);
        stepRepository.save(record);
        auditStep(principal, "developer_onboarding.step_skipped", definition.key());
        return workflowFor(principal);
    }

    @Transactional
    public DeveloperOnboardingWorkflowView completeOnboarding(AuthenticatedUser principal) {
        DeveloperOnboardingWorkflowView current = workflowFor(principal);
        List<String> missing = current.steps().stream()
                .filter(step -> step.required() && !step.key().equals(DeveloperOnboardingStepKey.COMPLETION.name()))
                .filter(step -> !"completed".equals(step.status()))
                .map(DeveloperOnboardingStepView::key)
                .toList();
        if (!missing.isEmpty()) {
            throw new BusinessException(HttpStatus.CONFLICT, "ONBOARDING_REQUIREMENTS_INCOMPLETE",
                    "Complete the required onboarding steps before finishing: " + String.join(", ", missing));
        }
        boolean productionRequested = isApplicable(
                DeveloperOnboardingStepKey.PRODUCTION_REQUEST, principal,
                statusService.statusFor(principal));
        if (productionRequested) {
            List<String> unresolvedProduction = current.steps().stream()
                    .filter(step -> Set.of("PRODUCTION_APPROVAL", "PRODUCTION_CREDENTIALS").contains(step.key()))
                    .filter(step -> isApplicableKey(step.key(), principal, statusService.statusFor(principal)))
                    .filter(step -> !"completed".equals(step.status()))
                    .map(DeveloperOnboardingStepView::key)
                    .toList();
            if (!unresolvedProduction.isEmpty()) {
                throw new BusinessException(HttpStatus.CONFLICT, "ONBOARDING_PRODUCTION_STEPS_INCOMPLETE",
                        "Resolve the production access steps before finishing: "
                                + String.join(", ", unresolvedProduction));
            }
        }

        statusService.complete(principal);
        Instant now = clock.instant();
        DeveloperOnboardingProgress progress = progressFor(principal);
        DeveloperOnboardingStepRecord completion = stepRepository
                .findByUserIdAndStepKey(principal.userId(), DeveloperOnboardingStepKey.COMPLETION.name())
                .orElseGet(() -> DeveloperOnboardingStepRecord.start(principal.userId(),
                        DeveloperOnboardingStepKey.COMPLETION.name(), true, false, now));
        completion.complete(now);
        stepRepository.save(completion);
        auditStep(principal, "developer_onboarding.completed", DeveloperOnboardingStepKey.COMPLETION);
        return workflowFor(principal);
    }

    private void auditStep(
            AuthenticatedUser principal, String action, DeveloperOnboardingStepKey key) {
        auditService.append(principal.organizationId(), principal.userId(), action,
                "developer_onboarding", key.name(), RequestContext.currentRequestId(),
                Map.of("step", key.name()));
    }

    private DeveloperOnboardingStepStatus resolveStatus(
            Definition definition, boolean applicable, boolean satisfied,
            DeveloperOnboardingStepRecord record, AuthenticatedUser principal,
            DeveloperOnboardingStatus status, DeveloperOnboardingProgress progress) {
        if (satisfied) {
            return DeveloperOnboardingStepStatus.COMPLETED;
        }
        if (record != null && record.getStatus() == DeveloperOnboardingStepStatus.SKIPPED
                && !definition.required()) {
            return DeveloperOnboardingStepStatus.SKIPPED;
        }
        if (!applicable && definition.conditional()) {
            return DeveloperOnboardingStepStatus.NOT_STARTED;
        }
        if (firstUnsatisfiedPrerequisite(definition, principal, status, progress) != null) {
            return DeveloperOnboardingStepStatus.BLOCKED;
        }
        return record == null ? DeveloperOnboardingStepStatus.NOT_STARTED : record.getStatus();
    }

    private String blockedReason(
            Definition definition, DeveloperOnboardingStepStatus status,
            AuthenticatedUser principal, DeveloperOnboardingStatus onboarding,
            DeveloperOnboardingProgress progress) {
        if (status != DeveloperOnboardingStepStatus.BLOCKED) {
            return null;
        }
        DeveloperOnboardingStepKey missing = firstUnsatisfiedPrerequisite(
                definition, principal, onboarding, progress);
        return missing == null ? reasonFor(definition.key())
                : "Complete " + missing.name().replace('_', ' ').toLowerCase(java.util.Locale.ROOT)
                        + " before continuing.";
    }

    private void requirePrerequisites(
            Definition definition, AuthenticatedUser principal,
            DeveloperOnboardingStatus status, DeveloperOnboardingProgress progress) {
        DeveloperOnboardingStepKey missing = firstUnsatisfiedPrerequisite(
                definition, principal, status, progress);
        if (missing != null) {
            throw blocked("Complete " + missing.name().replace('_', ' ').toLowerCase(java.util.Locale.ROOT)
                    + " before continuing.");
        }
    }

    private DeveloperOnboardingStepKey firstUnsatisfiedPrerequisite(
            Definition definition, AuthenticatedUser principal,
            DeveloperOnboardingStatus status, DeveloperOnboardingProgress progress) {
        for (DeveloperOnboardingStepKey prerequisite : definition.prerequisites()) {
            if (!isSatisfied(prerequisite, principal, status, progress)) {
                return prerequisite;
            }
        }
        return null;
    }

    private boolean isSatisfied(
            DeveloperOnboardingStepKey key, AuthenticatedUser principal,
            DeveloperOnboardingStatus status, DeveloperOnboardingProgress progress) {
        return switch (key) {
            case ACCOUNT -> true;
            case EMAIL_VERIFICATION -> status.emailVerified();
            case SECURITY -> mfaSecretRepository
                    .findByUserIdAndRevokedAtIsNullAndConfirmedAtIsNotNull(principal.userId()).isPresent();
            case PROFILE -> status.profileReady();
            case ORGANIZATION -> status.organizationReady();
            case PROJECT -> status.projectReady();
            case SANDBOX -> isActiveSandbox(principal, status);
            case API_KEY -> hasActiveKey(principal, status, EnvironmentType.SANDBOX);
            case FIRST_API_REQUEST -> hasSuccessfulSandboxRequest(principal, status);
            case API_EXPLORER -> completedExplicitly(principal, key);
            case INTEGRATION -> hasHealthyIntegration(principal, status);
            case WEBHOOK -> hasActiveWebhook(principal, status);
            case TEAM -> hasAdditionalActiveMember(principal);
            case DOCUMENTATION -> completedExplicitly(principal, key);
            case PRODUCTION_READINESS -> isProductionReady(principal, status);
            case PRODUCTION_REQUEST -> hasProductionRequest(principal, status);
            case PRODUCTION_APPROVAL -> hasProductionApproval(principal, status);
            case PRODUCTION_CREDENTIALS -> hasActiveKey(principal, status, EnvironmentType.PRODUCTION);
            case COMPLETION -> progress != null && progress.isCompleted();
        };
    }

    private boolean isApplicable(
            DeveloperOnboardingStepKey key, AuthenticatedUser principal, DeveloperOnboardingStatus status) {
        if (key == DeveloperOnboardingStepKey.PRODUCTION_REQUEST) {
            return hasProductionRequest(principal, status);
        }
        if (key == DeveloperOnboardingStepKey.PRODUCTION_APPROVAL) {
            return hasProductionRequest(principal, status);
        }
        if (key == DeveloperOnboardingStepKey.PRODUCTION_CREDENTIALS) {
            return hasProductionApproval(principal, status);
        }
        return true;
    }

    private boolean isApplicableKey(
            String key, AuthenticatedUser principal, DeveloperOnboardingStatus status) {
        return DeveloperOnboardingStepKey.parse(key)
                .map(parsed -> isApplicable(parsed, principal, status)).orElse(false);
    }

    private boolean isActiveSandbox(AuthenticatedUser principal, DeveloperOnboardingStatus status) {
        return status.projectId() != null && status.environmentId() != null
                && environmentRepository.findByIdAndOrganizationId(
                        status.environmentId(), principal.organizationId())
                        .filter(environment -> environment.getProjectId().equals(status.projectId()))
                        .filter(environment -> environment.getType() == EnvironmentType.SANDBOX)
                        .filter(environment -> "ACTIVE".equals(environment.getStatus().name()))
                        .isPresent();
    }

    private boolean hasActiveKey(
            AuthenticatedUser principal, DeveloperOnboardingStatus status, EnvironmentType type) {
        if (status.projectId() == null) {
            return false;
        }
        return environmentRepository.findByProjectIdAndType(status.projectId(), type)
                .filter(environment -> environment.getOrganizationId().equals(principal.organizationId()))
                .filter(environment -> "ACTIVE".equals(environment.getStatus().name()))
                .filter(environment -> apiKeyRepository
                        .findByOrganizationIdAndProjectIdAndEnvironmentIdOrderByCreatedAtDesc(
                                principal.organizationId(), status.projectId(), environment.getId())
                        .stream().anyMatch(key -> key.getStatus() == ApiKeyStatus.ACTIVE
                                && (key.getExpiresAt() == null || key.getExpiresAt().isAfter(clock.instant()))))
                .isPresent();
    }

    private boolean hasSuccessfulSandboxRequest(
            AuthenticatedUser principal, DeveloperOnboardingStatus status) {
        return isActiveSandbox(principal, status)
                && requestEventRepository.existsByOrganizationIdAndEnvironmentIdAndStatusCodeBetween(
                        principal.organizationId(), status.environmentId(), 200, 299);
    }

    private boolean hasHealthyIntegration(AuthenticatedUser principal, DeveloperOnboardingStatus status) {
        return status.projectId() != null
                && integrationRepository.findByOrganizationIdAndProjectIdOrderByCreatedAtDesc(
                        principal.organizationId(), status.projectId())
                        .stream().anyMatch(integration -> integration.isEnabled()
                                && integration.getStatus() == IntegrationStatus.CONNECTED
                                && integration.getLastSuccessAt() != null);
    }

    private boolean hasActiveWebhook(AuthenticatedUser principal, DeveloperOnboardingStatus status) {
        return status.projectId() != null
                && webhookRepository.existsByOrganizationIdAndProjectIdAndStatus(
                        principal.organizationId(), status.projectId(), "ACTIVE");
    }

    private boolean hasAdditionalActiveMember(AuthenticatedUser principal) {
        return membershipRepository.findAllByOrganizationId(principal.organizationId()).stream()
                .filter(membership -> membership.getStatus() == MembershipStatus.ACTIVE)
                .map(membership -> membership.getUser().getId())
                .distinct().count() > 1;
    }

    private boolean isProductionReady(AuthenticatedUser principal, DeveloperOnboardingStatus status) {
        return isSatisfied(DeveloperOnboardingStepKey.ACCOUNT, principal, status, null)
                && isSatisfied(DeveloperOnboardingStepKey.EMAIL_VERIFICATION, principal, status, null)
                && isSatisfied(DeveloperOnboardingStepKey.SECURITY, principal, status, null)
                && isSatisfied(DeveloperOnboardingStepKey.PROFILE, principal, status, null)
                && isSatisfied(DeveloperOnboardingStepKey.ORGANIZATION, principal, status, null)
                && isSatisfied(DeveloperOnboardingStepKey.PROJECT, principal, status, null)
                && isSatisfied(DeveloperOnboardingStepKey.SANDBOX, principal, status, null)
                && isSatisfied(DeveloperOnboardingStepKey.API_KEY, principal, status, null)
                && isSatisfied(DeveloperOnboardingStepKey.FIRST_API_REQUEST, principal, status, null);
    }

    private boolean hasProductionRequest(AuthenticatedUser principal, DeveloperOnboardingStatus status) {
        return status.projectId() != null && productionAccessRepository
                .findByOrganizationIdOrderByCreatedAtDesc(principal.organizationId()).stream()
                .anyMatch(request -> request.getProjectId().equals(status.projectId())
                        && request.getStatus() != ProductionAccessStatus.CANCELLED
                        && request.getStatus() != ProductionAccessStatus.REJECTED);
    }

    private boolean hasProductionApproval(AuthenticatedUser principal, DeveloperOnboardingStatus status) {
        return status.projectId() != null && productionAccessRepository
                .findByOrganizationIdOrderByCreatedAtDesc(principal.organizationId()).stream()
                .anyMatch(request -> request.getProjectId().equals(status.projectId())
                        && (request.getStatus() == ProductionAccessStatus.APPROVED
                                || request.getStatus() == ProductionAccessStatus.ACTIVE));
    }

    private boolean completedExplicitly(AuthenticatedUser principal, DeveloperOnboardingStepKey key) {
        return stepRepository.findByUserIdAndStepKey(principal.userId(), key.name())
                .map(record -> record.getStatus() == DeveloperOnboardingStepStatus.COMPLETED)
                .orElse(false);
    }

    private Instant satisfiedAt(
            DeveloperOnboardingStepRecord record, DeveloperOnboardingProgress progress,
            DeveloperOnboardingStepKey key) {
        if (record != null && record.getCompletedAt() != null) {
            return record.getCompletedAt();
        }
        return key == DeveloperOnboardingStepKey.COMPLETION && progress != null
                ? progress.getUpdatedAt() : null;
    }

    private Definition definition(String requestedStep) {
        DeveloperOnboardingStepKey key = DeveloperOnboardingStepKey.parse(requestedStep)
                .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST,
                        "ONBOARDING_STEP_INVALID", "The requested onboarding step is not supported."));
        return STEPS.stream().filter(step -> step.key() == key).findFirst().orElseThrow();
    }

    private DeveloperOnboardingProgress progressFor(AuthenticatedUser principal) {
        return progressRepository.findById(principal.userId()).orElseGet(
                () -> progressRepository.save(
                        DeveloperOnboardingProgress.start(principal.userId(), clock.instant())));
    }

    private String legacyStep(DeveloperOnboardingStepKey key) {
        return switch (key) {
            case ACCOUNT, EMAIL_VERIFICATION, SECURITY, PROFILE -> "profile";
            case ORGANIZATION -> "organization";
            case PROJECT -> "project";
            case SANDBOX -> "environment";
            case API_KEY -> "api-key";
            case FIRST_API_REQUEST, API_EXPLORER -> "first-request";
            case INTEGRATION -> "api-explorer";
            case WEBHOOK -> "webhook";
            case TEAM, DOCUMENTATION -> "documentation";
            case PRODUCTION_READINESS -> "production-readiness";
            case PRODUCTION_REQUEST, PRODUCTION_APPROVAL, PRODUCTION_CREDENTIALS -> "production-readiness";
            case COMPLETION -> "complete";
        };
    }

    private BusinessException blocked(String message) {
        return new BusinessException(HttpStatus.CONFLICT, "ONBOARDING_STEP_BLOCKED", message);
    }

    private String reasonFor(DeveloperOnboardingStepKey key) {
        return switch (key) {
            case ACCOUNT -> "An active developer account is required.";
            case EMAIL_VERIFICATION -> "Verify your email address before continuing.";
            case SECURITY -> "Confirm an authenticator-based MFA factor before continuing.";
            case PROFILE -> "Save a developer profile before continuing.";
            case ORGANIZATION -> "Complete the organization details before continuing.";
            case PROJECT -> "Create an active project before continuing.";
            case SANDBOX -> "Create an active sandbox environment before continuing.";
            case API_KEY -> "Create an active sandbox API key before continuing.";
            case FIRST_API_REQUEST -> "A successful sandbox API request must be recorded before continuing.";
            case API_EXPLORER -> "Open the API Explorer and confirm this step before continuing.";
            case INTEGRATION -> "Connect and successfully test an integration before continuing.";
            case WEBHOOK -> "Configure an active webhook endpoint before continuing.";
            case TEAM -> "Invite a teammate and wait for the invitation to be accepted.";
            case DOCUMENTATION -> "Acknowledge the developer documentation step before continuing.";
            case PRODUCTION_READINESS -> "Complete the backend-calculated production readiness requirements.";
            case PRODUCTION_REQUEST -> "Submit a production access request before continuing.";
            case PRODUCTION_APPROVAL -> "Production access must be approved before continuing.";
            case PRODUCTION_CREDENTIALS -> "Create an active production API key before continuing.";
            case COMPLETION -> "Complete the required onboarding steps before finishing.";
        };
    }
}
