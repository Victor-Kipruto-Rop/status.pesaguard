package com.pesaguard.backend.webhooks.api;

import java.util.List;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pesaguard.backend.common.api.ApiResponse;
import com.pesaguard.backend.security.principals.AuthenticatedUser;
import com.pesaguard.backend.webhooks.application.WebhookEndpointService;
import com.pesaguard.backend.webhooks.application.WebhookEndpointService.CreatedEndpoint;
import com.pesaguard.backend.webhooks.application.WebhookEndpointService.EndpointView;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/v1/webhooks/endpoints")
public class WebhookEndpointController {

    private final WebhookEndpointService service;

    public WebhookEndpointController(WebhookEndpointService service) {
        this.service = service;
    }

    @GetMapping
    ApiResponse<List<EndpointView>> list(@AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam UUID projectId, @RequestParam UUID environmentId) {
        return ApiResponse.of(service.list(principal, projectId, environmentId));
    }

    @PostMapping
    ApiResponse<CreatedEndpoint> create(@AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody CreateEndpointRequest request) {
        return ApiResponse.of(service.create(principal, request.projectId(), request.environmentId(),
                request.name(), request.url()));
    }

    @PatchMapping("/{endpointId}/status")
    ApiResponse<EndpointView> changeStatus(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID endpointId, @RequestParam UUID environmentId,
            @Valid @RequestBody ChangeEndpointStatusRequest request) {
        return ApiResponse.of(service.changeStatus(
                principal, endpointId, environmentId, request.status()));
    }

    @PatchMapping("/{endpointId}")
    ApiResponse<EndpointView> updateConfiguration(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID endpointId,
            @RequestParam UUID environmentId,
            @Valid @RequestBody UpdateEndpointRequest request) {
        return ApiResponse.of(service.updateConfiguration(
                principal, endpointId, environmentId, request.name(), request.url()));
    }

    @PostMapping("/{endpointId}/rotate-secret")
    ApiResponse<CreatedEndpoint> rotateSigningSecret(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID endpointId, @RequestParam UUID environmentId) {
        return ApiResponse.of(service.rotateSigningSecret(principal, endpointId, environmentId));
    }

    public record CreateEndpointRequest(
            @NotNull UUID projectId,
            @NotNull UUID environmentId,
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Size(max = 2048) String url) {
    }

    public record UpdateEndpointRequest(
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Size(max = 2048) String url) {
    }

    public record ChangeEndpointStatusRequest(@NotBlank String status) {
    }
}
