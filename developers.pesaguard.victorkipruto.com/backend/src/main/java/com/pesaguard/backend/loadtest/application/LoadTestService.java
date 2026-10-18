package com.pesaguard.backend.loadtest.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pesaguard.backend.audit.application.AuditService;
import com.pesaguard.backend.audit.domain.AuditEvent;
import com.pesaguard.backend.common.api.RequestContext;
import com.pesaguard.backend.common.exception.BusinessException;
import com.pesaguard.backend.common.exception.ResourceNotFoundException;
import com.pesaguard.backend.credentials.api.ApiKey;
import com.pesaguard.backend.credentials.api.ApiKeyRepository;
import com.pesaguard.backend.environment.api.EnvironmentApiBaseUrls;
import com.pesaguard.backend.environment.application.EnvironmentAccessPolicyService;
import com.pesaguard.backend.environment.domain.EnvironmentPermission;
import com.pesaguard.backend.environment.domain.EnvironmentStatus;
import com.pesaguard.backend.environment.domain.EnvironmentType;
import com.pesaguard.backend.environment.domain.ProjectEnvironment;
import com.pesaguard.backend.environment.infrastructure.ProjectEnvironmentRepository;
import com.pesaguard.backend.loadtest.api.CreateLoadTestRequest;
import com.pesaguard.backend.loadtest.api.CreateRunRequest;
import com.pesaguard.backend.loadtest.api.LoadGeneratorView;
import com.pesaguard.backend.loadtest.api.LoadTestMetricView;
import com.pesaguard.backend.loadtest.api.LoadTestResultView;
import com.pesaguard.backend.loadtest.api.LoadTestRunView;
import com.pesaguard.backend.loadtest.api.LoadTestView;
import com.pesaguard.backend.loadtest.domain.GeneratorStatus;
import com.pesaguard.backend.loadtest.domain.LoadGenerator;
import com.pesaguard.backend.loadtest.domain.LoadTestConfiguration;
import com.pesaguard.backend.loadtest.domain.LoadTestEvent;
import com.pesaguard.backend.loadtest.domain.LoadTestGeneratorAllocation;
import com.pesaguard.backend.loadtest.domain.LoadTestLifecycle;
import com.pesaguard.backend.loadtest.domain.LoadTestMetric;
import com.pesaguard.backend.loadtest.domain.LoadTestResult;
import com.pesaguard.backend.loadtest.domain.LoadTestRun;
import com.pesaguard.backend.loadtest.domain.LoadTestStage;
import com.pesaguard.backend.loadtest.domain.LoadTestThreshold;
import com.pesaguard.backend.loadtest.infrastructure.LoadGeneratorHeartbeatRepository;
import com.pesaguard.backend.loadtest.infrastructure.LoadGeneratorRepository;
import com.pesaguard.backend.loadtest.infrastructure.LoadTestConfigurationRepository;
import com.pesaguard.backend.loadtest.infrastructure.LoadTestEventRepository;
import com.pesaguard.backend.loadtest.infrastructure.LoadTestGeneratorAllocationRepository;
import com.pesaguard.backend.loadtest.infrastructure.LoadTestMetricRepository;
import com.pesaguard.backend.loadtest.infrastructure.LoadTestResultRepository;
import com.pesaguard.backend.loadtest.infrastructure.LoadTestRunRepository;
import com.pesaguard.backend.outbox.application.OutboxService;
import com.pesaguard.backend.project.application.ProjectAuthorization;
import com.pesaguard.backend.project.domain.Project;
import com.pesaguard.backend.project.domain.ProjectStatus;
import com.pesaguard.backend.project.infrastructure.ProjectRepository;
import com.pesaguard.backend.rbac.application.AuthorizationService;
import com.pesaguard.backend.rbac.domain.Permission;
import com.pesaguard.backend.security.principals.AuthenticatedUser;

@Service
public class LoadTestService {
    private static final String PRODUCTION_CONFIRMATION = "CONFIRM_PRODUCTION_LOAD_TEST";
    private static final List<LoadTestLifecycle> ACTIVE_RUN_STATES = List.of(
            LoadTestLifecycle.QUEUED, LoadTestLifecycle.PREPARING, LoadTestLifecycle.RUNNING,
            LoadTestLifecycle.PAUSED, LoadTestLifecycle.STOPPING);

    private final LoadTestConfigurationRepository testRepository;
    private final LoadTestRunRepository runRepository;
    private final LoadTestMetricRepository metricRepository;
    private final LoadTestResultRepository resultRepository;
    private final LoadTestEventRepository eventRepository;
    private final LoadTestGeneratorAllocationRepository allocationRepository;
    private final LoadGeneratorRepository generatorRepository;
    private final ProjectRepository projectRepository;
    private final ProjectEnvironmentRepository environmentRepository;
    private final ApiKeyRepository apiKeyRepository;
    private final AuthorizationService authorizationService;
    private final ProjectAuthorization projectAuthorization;
    private final EnvironmentAccessPolicyService environmentAccessPolicy;
    private final LoadTestRequestValidator validator;
    private final AuditService auditService;
    private final OutboxService outboxService;
    private final Clock clock;
    private final int maxVus;
    private final int maxRps;
    private final int maxDurationSeconds;
    private final int maxConcurrentRuns;
    private final long heartbeatExpirySeconds;

    public LoadTestService(
            LoadTestConfigurationRepository testRepository,
            LoadTestRunRepository runRepository,
            LoadTestMetricRepository metricRepository,
            LoadTestResultRepository resultRepository,
            LoadTestEventRepository eventRepository,
            LoadTestGeneratorAllocationRepository allocationRepository,
            LoadGeneratorRepository generatorRepository,
            ProjectRepository projectRepository,
            ProjectEnvironmentRepository environmentRepository,
            ApiKeyRepository apiKeyRepository,
            AuthorizationService authorizationService,
            ProjectAuthorization projectAuthorization,
            EnvironmentAccessPolicyService environmentAccessPolicy,
            LoadTestRequestValidator validator,
            AuditService auditService,
            OutboxService outboxService,
            Clock clock,
            @Value("${pesaguard.load-testing.max-vus:100000}") int maxVus,
            @Value("${pesaguard.load-testing.max-rps:100000}") int maxRps,
            @Value("${pesaguard.load-testing.max-duration-seconds:86400}") int maxDurationSeconds,
            @Value("${pesaguard.load-testing.max-concurrent-runs:3}") int maxConcurrentRuns,
            @Value("${pesaguard.load-testing.heartbeat-expiry-seconds:45}") long heartbeatExpirySeconds) {
        this.testRepository = testRepository;
        this.runRepository = runRepository;
        this.metricRepository = metricRepository;
        this.resultRepository = resultRepository;
        this.eventRepository = eventRepository;
        this.allocationRepository = allocationRepository;
        this.generatorRepository = generatorRepository;
        this.projectRepository = projectRepository;
        this.environmentRepository = environmentRepository;
        this.apiKeyRepository = apiKeyRepository;
        this.authorizationService = authorizationService;
        this.projectAuthorization = projectAuthorization;
        this.environmentAccessPolicy = environmentAccessPolicy;
        this.validator = validator;
        this.auditService = auditService;
        this.outboxService = outboxService;
        this.clock = clock;
        this.maxVus = maxVus;
        this.maxRps = maxRps;
        this.maxDurationSeconds = maxDurationSeconds;
        this.maxConcurrentRuns = maxConcurrentRuns;
        this.heartbeatExpirySeconds = heartbeatExpirySeconds;
    }

    @Transactional
    public LoadTestView create(AuthenticatedUser principal, CreateLoadTestRequest request) {
        ProjectEnvironment environment = validateConfiguration(principal, request);
        LoadTestConfiguration test = LoadTestConfiguration.create(
                principal.organizationId(), environment.getProjectId(), environment.getId(), principal.userId(),
                request.name(), request.description(), request.endpointPath(),
                request.httpMethod(), request.contentType(),
                request.headers() == null ? Map.of() : request.headers(),
                request.requestBody(), request.apiKeyId(), request.loadPattern(), request.targetVus(),
                request.targetRps(), request.maximumRps(), request.maximumDurationSeconds(),
                request.stages(), request.thresholds());
        test = testRepository.saveAndFlush(test);
        recordEvent(principal, environment, test, null, "load_test.created",
                Map.of("loadTestId", test.getId().toString()));
        return LoadTestView.from(test, environment);
    }

    @Transactional
    public LoadTestView update(AuthenticatedUser principal, UUID testId, CreateLoadTestRequest request) {
        LoadTestConfiguration test = requireTest(principal, testId, true);
        if (!test.getProjectId().equals(request.projectId())
                || !test.getEnvironmentId().equals(request.environmentId())) {
            throw new BusinessException(HttpStatus.CONFLICT, "LOAD_TEST_SCOPE_IMMUTABLE",
                    "A load-test configuration cannot be moved to another project or environment.");
        }
        if (!runRepository.findByLoadTestIdAndOrganizationIdAndProjectIdAndEnvironmentIdOrderByCreatedAtDesc(
                testId, principal.organizationId(), test.getProjectId(), test.getEnvironmentId()).isEmpty()) {
            throw new BusinessException(HttpStatus.CONFLICT, "LOAD_TEST_HAS_RUN_HISTORY",
                    "A configuration with run history cannot be edited. Clone it to create a new configuration.");
        }
        ProjectEnvironment environment = validateConfiguration(principal, request);
        test.update(request.name(), request.description(), request.endpointPath(), request.httpMethod(),
                request.contentType(), request.headers() == null ? Map.of() : request.headers(),
                request.requestBody(), request.apiKeyId(), request.loadPattern(), request.targetVus(),
                request.targetRps(), request.maximumRps(), request.maximumDurationSeconds(),
                request.stages(), request.thresholds());
        test = testRepository.saveAndFlush(test);
        recordEvent(principal, environment, test, null, "load_test.updated",
                Map.of("loadTestId", test.getId().toString()));
        return LoadTestView.from(test, environment);
    }

    @Transactional(readOnly = true)
    public List<LoadTestView> list(AuthenticatedUser principal, UUID projectId, UUID environmentId) {
        ProjectEnvironment environment = requireProjectEnvironment(
                principal, projectId, environmentId, false);
        return testRepository.findByOrganizationIdAndProjectIdAndEnvironmentIdOrderByCreatedAtDesc(
                        principal.organizationId(), projectId, environmentId)
                .stream().map(test -> LoadTestView.from(test, environment)).toList();
    }

    @Transactional(readOnly = true)
    public LoadTestView get(AuthenticatedUser principal, UUID testId) {
        LoadTestConfiguration test = requireTest(principal, testId, false);
        return LoadTestView.from(test, requireEnvironment(
                principal, test.getProjectId(), test.getEnvironmentId(), EnvironmentPermission.READ));
    }

    @Transactional
    public LoadTestView clone(AuthenticatedUser principal, UUID testId) {
        LoadTestConfiguration source = requireTest(principal, testId, true);
        authorizationService.requirePermission(principal, Permission.PROJECT_UPDATE);
        authorizationService.requirePermission(principal, Permission.ENVIRONMENT_UPDATE);
        ProjectEnvironment environment = requireEnvironment(
                principal, source.getProjectId(), source.getEnvironmentId(), EnvironmentPermission.WRITE);
        LoadTestConfiguration copy = LoadTestConfiguration.create(
                source.getOrganizationId(), source.getProjectId(), source.getEnvironmentId(), principal.userId(),
                source.getName() + " copy", source.getDescription(), source.getEndpointPath(),
                source.getHttpMethod(), source.getContentType(), source.getHeaders(), source.getRequestBody(),
                source.getApiKeyId(), source.getLoadPattern(), source.getTargetVus(), source.getTargetRps(),
                source.getMaximumRps(), source.getMaximumDurationSeconds(), source.getStages(), source.getThresholds());
        copy = testRepository.saveAndFlush(copy);
        recordEvent(principal, environment, copy, null, "load_test.cloned",
                Map.of("sourceLoadTestId", source.getId().toString(), "loadTestId", copy.getId().toString()));
        return LoadTestView.from(copy, environment);
    }

    @Transactional
    public void delete(AuthenticatedUser principal, UUID testId) {
        LoadTestConfiguration test = requireTest(principal, testId, true);
        authorizationService.requirePermission(principal, Permission.PROJECT_UPDATE);
        authorizationService.requirePermission(principal, Permission.ENVIRONMENT_UPDATE);
        ProjectEnvironment environment = requireEnvironment(
                principal, test.getProjectId(), test.getEnvironmentId(), EnvironmentPermission.WRITE);
        if (!runRepository.findByLoadTestIdAndOrganizationIdAndProjectIdAndEnvironmentIdOrderByCreatedAtDesc(
                testId, principal.organizationId(), test.getProjectId(), test.getEnvironmentId()).isEmpty()) {
            throw new BusinessException(HttpStatus.CONFLICT, "LOAD_TEST_HAS_RUN_HISTORY",
                    "A load-test configuration with run history cannot be deleted.");
        }
        testRepository.delete(test);
        recordEvent(principal, environment, test, null, "load_test.deleted",
                Map.of("loadTestId", testId.toString()));
    }

    @Transactional
    public LoadTestRunView startRun(AuthenticatedUser principal, UUID testId, CreateRunRequest request) {
        LoadTestConfiguration test = requireTest(principal, testId, true);
        authorizationService.requirePermission(principal, Permission.PROJECT_UPDATE);
        authorizationService.requirePermission(principal, Permission.ENVIRONMENT_UPDATE);
        ProjectEnvironment environment = requireEnvironment(
                principal, test.getProjectId(), test.getEnvironmentId(), EnvironmentPermission.WRITE);
        if (environment.getStatus() != EnvironmentStatus.ACTIVE) {
            throw bad("LOAD_TEST_ENVIRONMENT_INACTIVE", "Load tests require an active environment.");
        }
        if (environment.getType() == EnvironmentType.PRODUCTION) {
            authorizationService.requirePermission(principal, Permission.PRODUCTION_LAUNCH);
            if (request == null || !PRODUCTION_CONFIRMATION.equals(request.productionConfirmationToken())) {
                throw new BusinessException(HttpStatus.FORBIDDEN, "PRODUCTION_LOAD_TEST_CONFIRMATION_REQUIRED",
                        "Production execution requires explicit confirmation and production launch permission.");
            }
        }
        long activeCount = runRepository.countByOrganizationIdAndStatusIn(
                principal.organizationId(), ACTIVE_RUN_STATES);
        if (activeCount >= maxConcurrentRuns) {
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "LOAD_TEST_CONCURRENT_LIMIT_EXCEEDED",
                    "The organization has reached its concurrent load-test limit.");
        }
        Map<String, Object> snapshot = Map.of(
                "loadTestId", test.getId().toString(),
                "endpointPath", test.getEndpointPath(),
                "httpMethod", test.getHttpMethod(),
                "targetVus", test.getTargetVus(),
                "requestedRps", test.getTargetRps() == null ? 0 : test.getTargetRps(),
                "apiKeyId", test.getApiKeyId() == null ? "" : test.getApiKeyId().toString());
        long stageSeconds = test.getStages().stream().mapToLong(LoadTestStage::durationSeconds).sum();
        long estimatedSeconds = stageSeconds + 90L;
        LoadTestRun run = runRepository.saveAndFlush(
                LoadTestRun.queued(test, principal.userId(), snapshot, estimatedSeconds));
        allocateIfCapacityAllows(test, run);
        run = runRepository.saveAndFlush(run);
        recordEvent(principal, environment, test, run, "load_test.run.queued",
                Map.of("runId", run.getId().toString(),
                        "requestedVus", run.getRequestedVus(),
                        "allocatedVus", run.getAllocatedVus(),
                        "availabilityReason", run.getAvailabilityReason() == null ? "" : run.getAvailabilityReason()));
        return LoadTestRunView.from(run);
    }

    @Transactional(readOnly = true)
    public List<LoadTestRunView> listRuns(AuthenticatedUser principal, UUID testId) {
        LoadTestConfiguration test = requireTest(principal, testId, false);
        return runRepository.findByLoadTestIdAndOrganizationIdAndProjectIdAndEnvironmentIdOrderByCreatedAtDesc(
                        testId, principal.organizationId(), test.getProjectId(), test.getEnvironmentId())
                .stream().map(LoadTestRunView::from).toList();
    }

    @Transactional(readOnly = true)
    public LoadTestRunView getRun(AuthenticatedUser principal, UUID testId, UUID runId) {
        return LoadTestRunView.from(requireRun(principal, testId, runId));
    }

    @Transactional
    public LoadTestRunView stop(AuthenticatedUser principal, UUID testId, UUID runId) {
        LoadTestRun run = requireRun(principal, testId, runId, true);
        LoadTestConfiguration test = requireTest(principal, testId, true);
        ProjectEnvironment environment = requireEnvironment(
                principal, test.getProjectId(), test.getEnvironmentId(), EnvironmentPermission.WRITE);
        run.stop();
        if (run.getStatus() == LoadTestLifecycle.CANCELLED) releaseAllocations(runId);
        runRepository.saveAndFlush(run);
        recordEvent(principal, environment, test, run, "load_test.run.stop_requested",
                Map.of("runId", runId.toString(), "status", run.getStatus().name()));
        return LoadTestRunView.from(run);
    }

    @Transactional
    public LoadTestRunView cancel(AuthenticatedUser principal, UUID testId, UUID runId) {
        LoadTestRun run = requireRun(principal, testId, runId, true);
        LoadTestConfiguration test = requireTest(principal, testId, true);
        ProjectEnvironment environment = requireEnvironment(
                principal, test.getProjectId(), test.getEnvironmentId(), EnvironmentPermission.WRITE);
        run.cancel();
        releaseAllocations(runId);
        runRepository.saveAndFlush(run);
        recordEvent(principal, environment, test, run, "load_test.run.cancelled",
                Map.of("runId", runId.toString()));
        return LoadTestRunView.from(run);
    }

    @Transactional(readOnly = true)
    public List<LoadTestMetricView> metrics(AuthenticatedUser principal, UUID testId, UUID runId) {
        requireRun(principal, testId, runId);
        return metricRepository.findByRunIdOrderByObservedAtAsc(runId).stream()
                .map(LoadTestMetricView::from).toList();
    }

    @Transactional(readOnly = true)
    public LoadTestResultView results(AuthenticatedUser principal, UUID testId, UUID runId) {
        requireRun(principal, testId, runId);
        LoadTestResult result = resultRepository.findByRunId(runId)
                .orElseThrow(() -> new ResourceNotFoundException("Load-test results"));
        return new LoadTestResultView(result.getSummary(), result.getVerdict(), result.getRecordedAt());
    }

    @Transactional(readOnly = true)
    public List<LoadGeneratorView> generators(AuthenticatedUser principal, UUID projectId, UUID environmentId) {
        requireProjectEnvironment(principal, projectId, environmentId, false);
        Instant cutoff = clock.instant().minusSeconds(heartbeatExpirySeconds);
        return generatorRepository.findAll().stream()
                .map(generator -> LoadGeneratorView.from(generator,
                        generator.getLastHeartbeatAt() != null && generator.getLastHeartbeatAt().isAfter(cutoff)))
                .toList();
    }

    private void allocateIfCapacityAllows(LoadTestConfiguration test, LoadTestRun run) {
        Instant cutoff = clock.instant().minusSeconds(heartbeatExpirySeconds);
        List<LoadGenerator> generators = generatorRepository.findHealthyAvailable(GeneratorStatus.AVAILABLE, cutoff);
        if (generators.isEmpty()) {
            run.queueReason("NO_HEALTHY_GENERATORS");
            return;
        }
        if (test.getApiKeyId() != null) {
            run.queueReason("SECURE_SECRET_INJECTION_NOT_CONFIGURED");
            return;
        }
        int remainingVus = test.getTargetVus();
        int requestedRps = test.getTargetRps() != null
                ? test.getTargetRps()
                : test.getMaximumRps() == null ? 0 : test.getMaximumRps();
        int remainingRps = requestedRps;
        List<LoadTestGeneratorAllocation> plan = new ArrayList<>();
        for (LoadGenerator generator : generators) {
            int freeVus = Math.max(0, generator.getAvailableVus()
                    - (int) allocationRepository.sumUnclaimedVus(generator.getId()));
            int freeRps = Math.max(0, generator.getAvailableRps()
                    - (int) allocationRepository.sumUnclaimedRps(generator.getId()));
            int vus = Math.min(freeVus, remainingVus);
            int rps = requestedRps == 0 ? 0 : Math.min(freeRps, remainingRps);
            if (vus > 0 && (requestedRps == 0 || rps > 0)) {
                plan.add(LoadTestGeneratorAllocation.create(run.getId(), generator.getId(), vus, rps));
                remainingVus -= vus;
                remainingRps -= rps;
            }
            if (remainingVus == 0 && remainingRps == 0) break;
        }
        if (remainingVus > 0 || remainingRps > 0) {
            run.queueReason("INSUFFICIENT_HEALTHY_GENERATOR_CAPACITY");
            return;
        }
        allocationRepository.saveAll(plan);
        run.setAllocation(test.getTargetVus());
        run.queueReason("AWAITING_K6_GENERATOR_CLAIM");
    }

    private Project requireActiveProject(AuthenticatedUser principal, UUID projectId) {
        Project project = projectRepository.findByIdAndOrganizationId(projectId, principal.organizationId())
                .orElseThrow(() -> new ResourceNotFoundException("Project"));
        if (project.getStatus() != ProjectStatus.ACTIVE) {
            throw bad("LOAD_TEST_PROJECT_INACTIVE", "Load tests require an active project.");
        }
        return project;
    }

    private ProjectEnvironment requireProjectEnvironment(
            AuthenticatedUser principal, UUID projectId, UUID environmentId, boolean write) {
        authorizationService.requirePermission(principal, write ? Permission.PROJECT_UPDATE : Permission.PROJECT_READ);
        projectAuthorization.requireProjectRead(principal, projectId);
        requireActiveProject(principal, projectId);
        return requireEnvironment(principal, projectId, environmentId,
                write ? EnvironmentPermission.WRITE : EnvironmentPermission.READ);
    }

    private ProjectEnvironment validateConfiguration(
            AuthenticatedUser principal, CreateLoadTestRequest request) {
        authorizationService.requirePermission(principal, Permission.PROJECT_UPDATE);
        authorizationService.requirePermission(principal, Permission.ENVIRONMENT_UPDATE);
        projectAuthorization.requireProjectManage(principal, request.projectId());
        Project project = requireActiveProject(principal, request.projectId());
        ProjectEnvironment environment = requireEnvironment(
                principal, request.projectId(), request.environmentId(), EnvironmentPermission.WRITE);
        validator.validate(request, maxVus, maxRps, maxDurationSeconds);
        if (environment.getStatus() != EnvironmentStatus.ACTIVE) {
            throw bad("LOAD_TEST_ENVIRONMENT_INACTIVE", "Load tests require an active environment.");
        }
        if (request.apiKeyId() != null) {
            ApiKey key = apiKeyRepository.findByIdAndOrganizationIdAndProjectIdAndEnvironmentId(
                    request.apiKeyId(), principal.organizationId(), project.getId(), environment.getId())
                    .orElseThrow(() -> new ResourceNotFoundException("API key"));
            if (!key.isUsable(clock.instant())) {
                throw bad("LOAD_TEST_API_KEY_UNAVAILABLE", "The selected API key is not active.");
            }
        }
        return environment;
    }

    private ProjectEnvironment requireEnvironment(
            AuthenticatedUser principal, UUID projectId, UUID environmentId,
            EnvironmentPermission permission) {
        ProjectEnvironment environment = environmentRepository
                .findByIdAndOrganizationIdAndProjectId(environmentId, principal.organizationId(), projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Environment"));
        environmentAccessPolicy.requireAccess(principal, environment, permission);
        return environment;
    }

    private LoadTestConfiguration requireTest(AuthenticatedUser principal, UUID testId, boolean write) {
        LoadTestConfiguration test = testRepository.findByIdAndOrganizationId(testId, principal.organizationId())
                .orElseThrow(() -> new ResourceNotFoundException("Load test"));
        if (write) {
            authorizationService.requirePermission(principal, Permission.PROJECT_UPDATE);
            projectAuthorization.requireProjectManage(principal, test.getProjectId());
            requireActiveProject(principal, test.getProjectId());
            requireEnvironment(principal, test.getProjectId(), test.getEnvironmentId(), EnvironmentPermission.WRITE);
        } else {
            authorizationService.requirePermission(principal, Permission.PROJECT_READ);
            projectAuthorization.requireProjectRead(principal, test.getProjectId());
            requireActiveProject(principal, test.getProjectId());
            requireEnvironment(principal, test.getProjectId(), test.getEnvironmentId(), EnvironmentPermission.READ);
        }
        return test;
    }

    private LoadTestRun requireRun(AuthenticatedUser principal, UUID testId, UUID runId) {
        LoadTestConfiguration test = requireTest(principal, testId, false);
        return runRepository.findByIdAndLoadTestIdAndOrganizationIdAndProjectIdAndEnvironmentId(
                        runId, testId, principal.organizationId(), test.getProjectId(), test.getEnvironmentId())
                .orElseThrow(() -> new ResourceNotFoundException("Load-test run"));
    }

    private LoadTestRun requireRun(AuthenticatedUser principal, UUID testId, UUID runId, boolean write) {
        LoadTestConfiguration test = requireTest(principal, testId, write);
        return runRepository.findByIdAndLoadTestIdAndOrganizationIdAndProjectIdAndEnvironmentId(
                        runId, testId, principal.organizationId(), test.getProjectId(), test.getEnvironmentId())
                .orElseThrow(() -> new ResourceNotFoundException("Load-test run"));
    }

    private void releaseAllocations(UUID runId) {
        allocationRepository.findByRunIdOrderByGeneratorId(runId)
                .forEach(LoadTestGeneratorAllocation::release);
    }

    private void recordEvent(AuthenticatedUser principal, ProjectEnvironment environment,
            LoadTestConfiguration test, LoadTestRun run, String action, Map<String, Object> metadata) {
        UUID requestId = RequestContext.currentRequestId();
        auditService.append(principal.organizationId(), test.getProjectId(), principal.userId(),
                action, run == null ? "load_test" : "load_test_run",
                run == null ? test.getId().toString() : run.getId().toString(), requestId, metadata);
        eventRepository.save(LoadTestEvent.record(principal.organizationId(), test.getProjectId(),
                environment.getId(), test.getId(), run == null ? null : run.getId(), principal.userId(),
                action, metadata, clock.instant()));
        try {
            outboxService.recordTenantEvent(action, 1,
                    "{\"loadTestId\":\"" + test.getId() + "\",\"runId\":\""
                            + (run == null ? "" : run.getId()) + "\"}",
                    principal.organizationId(), test.getProjectId(), environment.getId(),
                    requestId.toString(), requestId.toString());
        } catch (RuntimeException ex) {
            throw ex;
        }
    }

    private static BusinessException bad(String code, String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, code, message);
    }
}
