package com.pesaguard.backend.onboarding.api;

import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.pesaguard.backend.common.api.ApiResponse;
import com.pesaguard.backend.credentials.api.CreatedApiKeyView;
import com.pesaguard.backend.environment.api.EnvironmentView;
import com.pesaguard.backend.onboarding.api.DeveloperWorkspaceBootstrapView;
import com.pesaguard.backend.onboarding.api.OnboardingApiKeyRequest;
import com.pesaguard.backend.onboarding.api.OnboardingEnvironmentRequest;
import com.pesaguard.backend.onboarding.api.UpdateOnboardingOrganizationRequest;
import com.pesaguard.backend.onboarding.api.UpdateOnboardingProgressRequest;
import com.pesaguard.backend.onboarding.application.DeveloperWorkspaceBootstrapService;
import com.pesaguard.backend.onboarding.application.DeveloperOnboardingStatusService;
import com.pesaguard.backend.onboarding.application.DeveloperOnboardingWorkflowService;
import com.pesaguard.backend.organization.api.OrganizationView;
import com.pesaguard.backend.organization.api.UpdateOrganizationRequest;
import com.pesaguard.backend.organization.application.OrganizationLifecycleService;
import com.pesaguard.backend.security.principals.AuthenticatedUser;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/onboarding")
public class DeveloperWorkspaceBootstrapController {

    private final DeveloperWorkspaceBootstrapService bootstrapService;
    private final DeveloperOnboardingStatusService onboardingStatusService;
    private final DeveloperOnboardingWorkflowService onboardingWorkflowService;
    private final OrganizationLifecycleService organizationLifecycleService;

    public DeveloperWorkspaceBootstrapController(
            DeveloperWorkspaceBootstrapService bootstrapService,
            DeveloperOnboardingStatusService onboardingStatusService,
            DeveloperOnboardingWorkflowService onboardingWorkflowService,
            OrganizationLifecycleService organizationLifecycleService) {
        this.bootstrapService = bootstrapService;
        this.onboardingStatusService = onboardingStatusService;
        this.onboardingWorkflowService = onboardingWorkflowService;
        this.organizationLifecycleService = organizationLifecycleService;
    }

    @GetMapping
    ApiResponse<DeveloperOnboardingWorkflowView> workflow(
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return ApiResponse.of(onboardingWorkflowService.workflowFor(principal));
    }

    @PostMapping("/steps/{step}/start")
    ApiResponse<DeveloperOnboardingWorkflowView> startStep(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String step) {
        return ApiResponse.of(onboardingWorkflowService.startStep(principal, step));
    }

    @PostMapping("/steps/{step}/complete")
    ApiResponse<DeveloperOnboardingWorkflowView> completeStep(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String step) {
        return ApiResponse.of(onboardingWorkflowService.completeStep(principal, step));
    }

    @PostMapping("/steps/{step}/skip")
    ApiResponse<DeveloperOnboardingWorkflowView> skipStep(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String step) {
        return ApiResponse.of(onboardingWorkflowService.skipStep(principal, step));
    }

    @GetMapping("/status")
    ApiResponse<DeveloperOnboardingStatus> status(
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return ApiResponse.of(onboardingStatusService.statusFor(principal));
    }

    @PatchMapping("/profile")
    ApiResponse<DeveloperOnboardingStatus> updateProfile(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody UpdateDeveloperProfileRequest request) {
        return ApiResponse.of(onboardingStatusService.updateProfile(principal, request));
    }

    @PatchMapping("/organization")
    ApiResponse<OrganizationView> updateOrganization(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody UpdateOnboardingOrganizationRequest request) {
        OrganizationView current = organizationLifecycleService.current(principal);
        Map<String, Object> metadata = new java.util.LinkedHashMap<>(current.metadata());
        metadata.put("description", request.description().trim());
        OrganizationView updated = organizationLifecycleService.update(principal,
                new UpdateOrganizationRequest(request.name().trim(), current.type(), metadata));
        onboardingStatusService.recordProgress(principal,
                new UpdateOnboardingProgressRequest("project", "organization"));
        return ApiResponse.of(updated);
    }

    @PostMapping("/bootstrap")
    ResponseEntity<ApiResponse<DeveloperWorkspaceBootstrapView>> bootstrap(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody BootstrapDeveloperWorkspaceRequest request) {
        return ResponseEntity.status(201)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.of(bootstrapService.bootstrap(principal, request)));
    }

    @PostMapping("/projects/{projectId}/environment")
    ResponseEntity<ApiResponse<EnvironmentView>> createEnvironment(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID projectId,
            @Valid @RequestBody OnboardingEnvironmentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.of(bootstrapService.createEnvironment(principal, projectId, request)));
    }

    @PostMapping("/api-key")
    ResponseEntity<ApiResponse<CreatedApiKeyView>> createApiKey(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody OnboardingApiKeyRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.of(bootstrapService.createFirstApiKey(principal, request)));
    }

    @PatchMapping("/progress")
    ApiResponse<DeveloperOnboardingStatus> updateProgress(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody UpdateOnboardingProgressRequest request) {
        return ApiResponse.of(onboardingStatusService.recordProgress(principal, request));
    }

    @PostMapping("/skip")
    ApiResponse<DeveloperOnboardingStatus> skip(
            @AuthenticationPrincipal AuthenticatedUser principal) {
        throw new com.pesaguard.backend.common.exception.BusinessException(
                HttpStatus.CONFLICT, "ONBOARDING_OPTIONAL_STEP_REQUIRED",
                "Skip a specific optional step using its step endpoint; required onboarding cannot be skipped.");
    }

    @PostMapping("/resume")
    ApiResponse<DeveloperOnboardingStatus> resume(
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return ApiResponse.of(onboardingStatusService.resume(principal));
    }

    @PostMapping("/complete")
    ApiResponse<DeveloperOnboardingStatus> complete(
            @AuthenticationPrincipal AuthenticatedUser principal) {
        onboardingWorkflowService.completeOnboarding(principal);
        return ApiResponse.of(onboardingStatusService.statusFor(principal));
    }
}
