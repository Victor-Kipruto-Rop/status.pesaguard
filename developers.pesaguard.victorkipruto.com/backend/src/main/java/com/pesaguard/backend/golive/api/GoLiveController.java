package com.pesaguard.backend.golive.api;

import java.util.List;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pesaguard.backend.common.api.ApiResponse;
import com.pesaguard.backend.golive.application.GoLiveService;
import com.pesaguard.backend.security.principals.AuthenticatedUser;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/environments/{environmentId}/go-live")
public class GoLiveController {

    private final GoLiveService goLiveService;

    public GoLiveController(GoLiveService goLiveService) {
        this.goLiveService = goLiveService;
    }

    @GetMapping("/readiness")
    ApiResponse<GoLiveReadinessView> readiness(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID projectId, @PathVariable UUID environmentId) {
        return ApiResponse.of(goLiveService.readiness(principal, projectId, environmentId));
    }

    @PostMapping("/verifications")
    ApiResponse<GoLiveVerificationJobView> verify(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID projectId, @PathVariable UUID environmentId,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return ApiResponse.of(goLiveService.startVerification(
                principal, projectId, environmentId, idempotencyKey));
    }

    @GetMapping("/verifications/{jobId}")
    ApiResponse<GoLiveVerificationJobView> verificationJob(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID projectId, @PathVariable UUID environmentId,
            @PathVariable UUID jobId) {
        return ApiResponse.of(goLiveService.verificationJob(
                principal, projectId, environmentId, jobId));
    }

    @GetMapping("/verifications")
    ApiResponse<List<GoLiveReadinessView>> verifications(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID projectId, @PathVariable UUID environmentId) {
        return ApiResponse.of(goLiveService.verifications(principal, projectId, environmentId));
    }

    @PostMapping("/launches")
    ApiResponse<GoLiveLaunchView> launch(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID projectId, @PathVariable UUID environmentId,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return ApiResponse.of(goLiveService.launch(principal, projectId, environmentId, idempotencyKey));
    }

    @GetMapping("/launches")
    ApiResponse<List<GoLiveLaunchView>> launches(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID projectId, @PathVariable UUID environmentId) {
        return ApiResponse.of(goLiveService.launches(principal, projectId, environmentId));
    }

    @PostMapping("/suspend")
    ApiResponse<GoLiveReadinessView> suspend(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID projectId, @PathVariable UUID environmentId) {
        return ApiResponse.of(goLiveService.suspend(principal, projectId, environmentId));
    }

    @PostMapping("/resume")
    ApiResponse<GoLiveReadinessView> resume(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID projectId, @PathVariable UUID environmentId) {
        return ApiResponse.of(goLiveService.resume(principal, projectId, environmentId));
    }

    @PostMapping("/revoke")
    ApiResponse<GoLiveLaunchView> revoke(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID projectId, @PathVariable UUID environmentId) {
        return ApiResponse.of(goLiveService.revoke(principal, projectId, environmentId));
    }
}
