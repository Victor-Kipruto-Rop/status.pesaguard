package com.pesaguard.backend.environment.api;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pesaguard.backend.common.api.ApiResponse;
import com.pesaguard.backend.environment.application.EnvironmentCredentialService;
import com.pesaguard.backend.security.principals.AuthenticatedUser;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/environments/{environmentId}/credentials")
public class EnvironmentCredentialController {

    private final EnvironmentCredentialService credentialService;

    public EnvironmentCredentialController(EnvironmentCredentialService credentialService) {
        this.credentialService = credentialService;
    }

    @GetMapping
    ResponseEntity<ApiResponse<List<EnvironmentCredentialView>>> list(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID projectId, @PathVariable UUID environmentId) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.of(credentialService.list(principal, projectId, environmentId)));
    }

    @PostMapping
    ResponseEntity<ApiResponse<EnvironmentCredentialView>> create(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID projectId, @PathVariable UUID environmentId,
            @Valid @RequestBody CreateEnvironmentCredentialRequest request) {
        return ResponseEntity.status(201).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.of(credentialService.create(principal, projectId, environmentId,
                        request.name(), request.type(), request.secret())));
    }

    @DeleteMapping("/{credentialId}")
    ResponseEntity<Void> revoke(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID projectId, @PathVariable UUID environmentId,
            @PathVariable UUID credentialId) {
        credentialService.revoke(principal, projectId, environmentId, credentialId);
        return ResponseEntity.noContent().build();
    }
}
