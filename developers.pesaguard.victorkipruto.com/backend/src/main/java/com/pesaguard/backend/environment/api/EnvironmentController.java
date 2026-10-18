package com.pesaguard.backend.environment.api;

import java.util.List;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pesaguard.backend.common.api.ApiResponse;
import com.pesaguard.backend.environment.application.EnvironmentService;
import com.pesaguard.backend.environment.application.EnvironmentAccessPolicyService;
import com.pesaguard.backend.security.principals.AuthenticatedUser;

import jakarta.validation.Valid;

/**
 * Environment endpoints enforce authorization in the service layer against the
 * permission catalog, so the controller does not duplicate role checks.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/environments")
public class EnvironmentController {

    private final EnvironmentService environmentService;
    private final EnvironmentAccessPolicyService accessPolicyService;

    public EnvironmentController(EnvironmentService environmentService,
            EnvironmentAccessPolicyService accessPolicyService) {
        this.environmentService = environmentService;
        this.accessPolicyService = accessPolicyService;
    }

    @PostMapping
    ApiResponse<EnvironmentView> create(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID projectId, @Valid @RequestBody CreateEnvironmentRequest request) {
        return ApiResponse.of(environmentService.create(principal, projectId, request));
    }

    @GetMapping
    ApiResponse<List<EnvironmentView>> list(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID projectId) {
        return ApiResponse.of(environmentService.list(principal, projectId));
    }

    @GetMapping("/{environmentId}")
    ApiResponse<EnvironmentView> get(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID projectId, @PathVariable UUID environmentId) {
        return ApiResponse.of(environmentService.get(principal, projectId, environmentId));
    }

    @GetMapping("/{environmentId}/access-policies")
    ApiResponse<List<EnvironmentAccessPolicyView>> accessPolicies(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID projectId,
            @PathVariable UUID environmentId) {
        return ApiResponse.of(accessPolicyService.list(principal, projectId, environmentId));
    }

    @PutMapping("/{environmentId}/access-policies")
    ApiResponse<EnvironmentAccessPolicyView> upsertAccessPolicy(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID projectId,
            @PathVariable UUID environmentId,
            @Valid @RequestBody UpsertEnvironmentAccessPolicyRequest request) {
        return ApiResponse.of(accessPolicyService.upsert(principal, projectId, environmentId, request));
    }

    @DeleteMapping("/{environmentId}/access-policies/{policyId}")
    ApiResponse<String> deleteAccessPolicy(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID projectId, @PathVariable UUID environmentId, @PathVariable UUID policyId) {
        accessPolicyService.delete(principal, projectId, environmentId, policyId);
        return ApiResponse.of("deleted");
    }

    @PutMapping("/{environmentId}/configuration")
    ApiResponse<EnvironmentView> updateConfiguration(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID projectId, @PathVariable UUID environmentId,
            @Valid @RequestBody UpdateEnvironmentConfigurationRequest request) {
        return ApiResponse.of(environmentService.updateConfiguration(principal, projectId, environmentId, request));
    }

    @GetMapping("/{environmentId}/limits")
    ApiResponse<EnvironmentLimitsView> limits(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID projectId, @PathVariable UUID environmentId) {
        return ApiResponse.of(environmentService.limits(principal, projectId, environmentId));
    }

    @PutMapping("/{environmentId}/limits")
    ApiResponse<EnvironmentLimitsView> updateLimits(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID projectId, @PathVariable UUID environmentId,
            @Valid @RequestBody UpdateEnvironmentLimitsRequest request) {
        return ApiResponse.of(environmentService.updateLimits(principal, projectId, environmentId, request));
    }

    @PostMapping("/{environmentId}/suspend")
    ApiResponse<EnvironmentView> suspend(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID projectId, @PathVariable UUID environmentId) {
        return ApiResponse.of(environmentService.suspend(principal, projectId, environmentId));
    }

    @PostMapping("/{environmentId}/resume")
    ApiResponse<EnvironmentView> resume(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID projectId, @PathVariable UUID environmentId) {
        return ApiResponse.of(environmentService.resume(principal, projectId, environmentId));
    }

    @PostMapping("/{environmentId}/deactivate")
    ApiResponse<EnvironmentView> deactivate(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID projectId, @PathVariable UUID environmentId) {
        return ApiResponse.of(environmentService.deactivate(principal, projectId, environmentId));
    }

    @PostMapping("/{environmentId}/promote")
    ApiResponse<EnvironmentView> promote(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID projectId, @PathVariable UUID environmentId,
            @Valid @RequestBody PromoteEnvironmentRequest request) {
        return ApiResponse.of(environmentService.promote(principal, projectId, environmentId, request));
    }

    @GetMapping("/{environmentId}/history")
    ApiResponse<List<EnvironmentHistoryView>> history(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID projectId, @PathVariable UUID environmentId) {
        return ApiResponse.of(environmentService.history(principal, projectId, environmentId));
    }
}
