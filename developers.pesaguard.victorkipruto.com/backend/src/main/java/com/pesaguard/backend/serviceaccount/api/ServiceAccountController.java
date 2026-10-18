package com.pesaguard.backend.serviceaccount.api;

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
import org.springframework.web.bind.annotation.RestController;

import com.pesaguard.backend.common.api.ApiResponse;
import com.pesaguard.backend.security.principals.AuthenticatedUser;
import com.pesaguard.backend.serviceaccount.application.ServiceAccountService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/service-accounts")
public class ServiceAccountController {

    private final ServiceAccountService service;

    public ServiceAccountController(ServiceAccountService service) {
        this.service = service;
    }

    @PostMapping
    ResponseEntity<ApiResponse<CreatedServiceAccountView>> create(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody ServiceAccountRequest request) {
        return ResponseEntity.status(201)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.of(service.create(principal, request)));
    }

    @GetMapping
    ApiResponse<List<ServiceAccountView>> list(@AuthenticationPrincipal AuthenticatedUser principal) {
        return ApiResponse.of(service.list(principal));
    }

    @PutMapping("/{accountId}")
    ApiResponse<ServiceAccountView> update(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID accountId,
            @Valid @RequestBody ServiceAccountRequest request) {
        return ApiResponse.of(service.update(principal, accountId, request));
    }

    @PostMapping("/{accountId}/rotate-secret")
    ResponseEntity<ApiResponse<CreatedServiceAccountView>> rotate(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID accountId) {
        return ResponseEntity.status(201)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.of(service.rotateSecret(principal, accountId)));
    }

    @PostMapping("/{accountId}/suspend")
    ApiResponse<ServiceAccountView> suspend(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID accountId) {
        return ApiResponse.of(service.suspend(principal, accountId));
    }

    @PostMapping("/{accountId}/resume")
    ApiResponse<ServiceAccountView> resume(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID accountId) {
        return ApiResponse.of(service.resume(principal, accountId));
    }

    @DeleteMapping("/{accountId}")
    ResponseEntity<Void> revoke(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID accountId) {
        service.revoke(principal, accountId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/token")
    ResponseEntity<ApiResponse<ServiceAccountTokenView>> issueToken(
            @Valid @RequestBody ServiceAccountTokenRequest request,
            jakarta.servlet.http.HttpServletRequest httpRequest) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.of(service.issueAccessToken(request, httpRequest.getRemoteAddr())));
    }
}
