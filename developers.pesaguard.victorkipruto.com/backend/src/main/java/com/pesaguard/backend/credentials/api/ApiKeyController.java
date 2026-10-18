package com.pesaguard.backend.credentials.api;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.pesaguard.backend.common.api.ApiResponse;
import com.pesaguard.backend.security.principals.AuthenticatedUser;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/environments/{environmentId}/api-keys")
public class ApiKeyController {

    private final ApiKeyService apiKeyService;
    private final Clock clock;

    public ApiKeyController(ApiKeyService apiKeyService, Clock clock) {
        this.apiKeyService = apiKeyService;
        this.clock = clock;
    }

    @PostMapping
    ResponseEntity<ApiResponse<CreatedApiKeyView>> issue(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID projectId,
            @PathVariable UUID environmentId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateApiKeyRequest request) {
        CreatedApiKeyView created = apiKeyService.issue(
                principal, projectId, environmentId, request, clock.instant(), idempotencyKey);
        return ResponseEntity.status(201)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.of(created));
    }

    @GetMapping
    ApiResponse<List<ApiKeyView>> list(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID projectId,
            @PathVariable UUID environmentId) {
        return ApiResponse.of(apiKeyService.list(principal, projectId, environmentId));
    }

    @PostMapping("/{keyId}/suspend")
    ApiResponse<ApiKeyView> suspend(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID projectId, @PathVariable UUID environmentId, @PathVariable UUID keyId) {
        return ApiResponse.of(apiKeyService.suspend(principal, projectId, environmentId, keyId, clock.instant()));
    }

    @PostMapping("/{keyId}/resume")
    ApiResponse<ApiKeyView> resume(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID projectId, @PathVariable UUID environmentId, @PathVariable UUID keyId) {
        return ApiResponse.of(apiKeyService.resume(principal, projectId, environmentId, keyId));
    }

    @PutMapping("/{keyId}/restrictions")
    ApiResponse<ApiKeyView> updateRestrictions(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID projectId, @PathVariable UUID environmentId, @PathVariable UUID keyId,
            @Valid @RequestBody UpdateApiKeyRestrictionsRequest request) {
        return ApiResponse.of(apiKeyService.updateRestrictions(
                principal, projectId, environmentId, keyId, request, clock.instant()));
    }

    @PutMapping("/{keyId}/name")
    ApiResponse<ApiKeyView> rename(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID projectId, @PathVariable UUID environmentId, @PathVariable UUID keyId,
            @Valid @RequestBody RenameApiKeyRequest request) {
        return ApiResponse.of(apiKeyService.rename(
                principal, projectId, environmentId, keyId, request, clock.instant()));
    }

    @PostMapping("/{keyId}/compromise")
    ApiResponse<ApiKeyView> markCompromised(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID projectId, @PathVariable UUID environmentId, @PathVariable UUID keyId) {
        return ApiResponse.of(apiKeyService.markCompromised(
                principal, projectId, environmentId, keyId, clock.instant()));
    }

    @PostMapping("/{keyId}/rotate")
    ResponseEntity<ApiResponse<CreatedApiKeyView>> rotate(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID projectId, @PathVariable UUID environmentId, @PathVariable UUID keyId) {
        return ResponseEntity.status(201)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.of(apiKeyService.rotate(principal, projectId, environmentId, keyId,
                        clock.instant())));
    }

    @GetMapping("/{keyId}/history")
    ApiResponse<List<ApiKeyHistoryView>> history(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID projectId, @PathVariable UUID environmentId, @PathVariable UUID keyId) {
        return ApiResponse.of(apiKeyService.history(principal, projectId, environmentId, keyId));
    }

    @DeleteMapping("/{keyId}")
    ResponseEntity<Void> revoke(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID projectId,
            @PathVariable UUID environmentId,
            @PathVariable UUID keyId) {
        apiKeyService.revoke(principal, projectId, environmentId, keyId, clock.instant());
        return ResponseEntity.noContent().build();
    }
}
