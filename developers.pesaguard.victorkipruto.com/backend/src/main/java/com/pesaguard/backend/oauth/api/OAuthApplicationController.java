package com.pesaguard.backend.oauth.api;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pesaguard.backend.common.api.ApiResponse;
import com.pesaguard.backend.oauth.application.OAuthApplicationService;
import com.pesaguard.backend.security.principals.AuthenticatedUser;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/oauth/applications")
public class OAuthApplicationController {

    private final OAuthApplicationService applicationService;

    public OAuthApplicationController(OAuthApplicationService applicationService) {
        this.applicationService = applicationService;
    }

    /** Returns the client secret exactly once; it is never retrievable later. */
    @PostMapping
    ResponseEntity<ApiResponse<CreatedApplicationView>> register(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody CreateApplicationRequest request) {
        return ResponseEntity.status(201)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.of(applicationService.register(principal, request)));
    }

    @GetMapping
    ApiResponse<List<ApplicationView>> list(@AuthenticationPrincipal AuthenticatedUser principal) {
        return ApiResponse.of(applicationService.list(principal));
    }

    @PatchMapping("/{applicationId}")
    ApiResponse<ApplicationView> update(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID applicationId, @Valid @RequestBody CreateApplicationRequest request) {
        return ApiResponse.of(applicationService.update(principal, applicationId, request));
    }

    @PostMapping("/{applicationId}/verify")
    ApiResponse<ApplicationView> verify(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID applicationId, @Valid @RequestBody VerifyApplicationRequest request) {
        return ApiResponse.of(applicationService.verify(principal, applicationId, request));
    }

    @PostMapping("/{applicationId}/rotate-secret")
    ResponseEntity<ApiResponse<CreatedApplicationView>> rotateSecret(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID applicationId) {
        return ResponseEntity.status(201)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.of(applicationService.rotateSecret(principal, applicationId)));
    }

    @PostMapping("/{applicationId}/suspend")
    ApiResponse<ApplicationView> suspend(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID applicationId) {
        return ApiResponse.of(applicationService.suspend(principal, applicationId));
    }

    @PostMapping("/{applicationId}/resume")
    ApiResponse<ApplicationView> resume(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID applicationId) {
        return ApiResponse.of(applicationService.resume(principal, applicationId));
    }

    @PostMapping("/{applicationId}/revoke")
    ApiResponse<ApplicationView> revoke(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID applicationId) {
        return ApiResponse.of(applicationService.revoke(principal, applicationId));
    }
}