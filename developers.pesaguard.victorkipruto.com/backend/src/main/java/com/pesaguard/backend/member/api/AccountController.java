package com.pesaguard.backend.member.api;

import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pesaguard.backend.common.api.ApiResponse;
import com.pesaguard.backend.member.application.UserLifecycleService;
import com.pesaguard.backend.security.principals.AuthenticatedUser;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/account")
public class AccountController {

    private final UserLifecycleService lifecycleService;

    public AccountController(UserLifecycleService lifecycleService) {
        this.lifecycleService = lifecycleService;
    }

    @GetMapping("/lifecycle")
    public ApiResponse<AccountLifecycleView> lifecycle(
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return ApiResponse.of(lifecycleService.lifecycle(principal.userId()));
    }

    @PostMapping("/deactivate")
    public ResponseEntity<Void> deactivate(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody AccountPauseRequest request) {
        lifecycleService.deactivate(principal.userId(), principal.organizationId(),
                request.currentPassword(), request.mfaCode(), request.durationDays());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/deletion")
    public ResponseEntity<ApiResponse<AccountLifecycleView>> requestDeletion(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody AccountStepUpRequest request) {
        lifecycleService.requestDeletion(principal.userId(), principal.organizationId(),
                request.currentPassword(), request.mfaCode());
        return ResponseEntity.accepted()
                .body(ApiResponse.of(lifecycleService.lifecycle(principal.userId())));
    }

    @PostMapping("/deletion/cancel")
    public ApiResponse<AccountLifecycleView> cancelDeletion(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody AccountStepUpRequest request) {
        lifecycleService.cancelDeletion(principal.userId(), principal.organizationId(),
                request.currentPassword(), request.mfaCode());
        return ApiResponse.of(lifecycleService.lifecycle(principal.userId()));
    }

    @PostMapping("/export")
    public ResponseEntity<AccountDataExport> export(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody AccountStepUpRequest request) {
        AccountDataExport export = lifecycleService.exportAccount(principal.userId(), principal.organizationId(),
                request.currentPassword(), request.mfaCode());
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"pesaguard-account-export.json\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store, no-cache, must-revalidate")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header(HttpHeaders.EXPIRES, "0")
                .body(export);
    }
}
