package com.pesaguard.backend.integration.api;

import java.util.List;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pesaguard.backend.common.api.ApiResponse;
import com.pesaguard.backend.integration.application.IntegrationManagementService;
import com.pesaguard.backend.security.principals.AuthenticatedUser;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/integrations")
public class ProjectIntegrationController {

    private final IntegrationManagementService integrations;

    public ProjectIntegrationController(IntegrationManagementService integrations) {
        this.integrations = integrations;
    }

    @GetMapping
    ResponseEntity<ApiResponse<List<IntegrationView>>> list(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID projectId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.of(integrations.list(principal, projectId)));
    }

    @GetMapping("/{integrationId}")
    ResponseEntity<ApiResponse<IntegrationView>> get(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID projectId, @PathVariable UUID integrationId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.of(integrations.get(principal, projectId, integrationId)));
    }

    @PostMapping("/{integrationId}/test")
    ResponseEntity<ApiResponse<IntegrationTestResultView>> test(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID projectId, @PathVariable UUID integrationId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.of(integrations.test(principal, projectId, integrationId)));
    }

    @GetMapping("/{integrationId}/health")
    ResponseEntity<ApiResponse<IntegrationHealthView>> health(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID projectId, @PathVariable UUID integrationId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.of(integrations.health(principal, projectId, integrationId)));
    }

    @GetMapping("/{integrationId}/tests")
    ResponseEntity<ApiResponse<List<IntegrationTestRunView>>> tests(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID projectId, @PathVariable UUID integrationId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.of(integrations.tests(principal, projectId, integrationId)));
    }

    @GetMapping("/{integrationId}/events")
    ResponseEntity<ApiResponse<List<IntegrationEventView>>> events(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID projectId, @PathVariable UUID integrationId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.of(integrations.events(principal, projectId, integrationId)));
    }

    @PostMapping("/{integrationId}/enable")
    ResponseEntity<Void> enable(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID projectId, @PathVariable UUID integrationId) {
        integrations.setEnabled(principal, projectId, integrationId, true);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping("/{integrationId}/disable")
    ResponseEntity<Void> disable(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID projectId, @PathVariable UUID integrationId) {
        integrations.setEnabled(principal, projectId, integrationId, false);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
