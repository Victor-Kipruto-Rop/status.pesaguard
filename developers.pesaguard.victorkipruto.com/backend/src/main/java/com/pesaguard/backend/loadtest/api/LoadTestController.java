package com.pesaguard.backend.loadtest.api;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pesaguard.backend.common.api.ApiResponse;
import com.pesaguard.backend.loadtest.application.LoadTestService;
import com.pesaguard.backend.security.principals.AuthenticatedUser;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/load-tests")
public class LoadTestController {
    private final LoadTestService loadTestService;

    public LoadTestController(LoadTestService loadTestService) {
        this.loadTestService = loadTestService;
    }

    @GetMapping
    ApiResponse<List<LoadTestView>> list(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam UUID projectId,
            @RequestParam UUID environmentId) {
        return ApiResponse.of(loadTestService.list(principal, projectId, environmentId));
    }

    @PostMapping
    ApiResponse<LoadTestView> create(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody CreateLoadTestRequest request) {
        return ApiResponse.of(loadTestService.create(principal, request));
    }

    @GetMapping("/{testId}")
    ApiResponse<LoadTestView> get(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID testId) {
        return ApiResponse.of(loadTestService.get(principal, testId));
    }

    @PutMapping("/{testId}")
    ApiResponse<LoadTestView> update(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID testId,
            @Valid @RequestBody CreateLoadTestRequest request) {
        return ApiResponse.of(loadTestService.update(principal, testId, request));
    }

    @DeleteMapping("/{testId}")
    ResponseEntity<Void> delete(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID testId) {
        loadTestService.delete(principal, testId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{testId}/clone")
    ApiResponse<LoadTestView> clone(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID testId) {
        return ApiResponse.of(loadTestService.clone(principal, testId));
    }

    @GetMapping("/{testId}/runs")
    ApiResponse<List<LoadTestRunView>> listRuns(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID testId) {
        return ApiResponse.of(loadTestService.listRuns(principal, testId));
    }

    @PostMapping("/{testId}/runs")
    ApiResponse<LoadTestRunView> startRun(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID testId,
            @Valid @RequestBody(required = false) CreateRunRequest request) {
        return ApiResponse.of(loadTestService.startRun(principal, testId, request));
    }

    @GetMapping("/{testId}/runs/{runId}")
    ApiResponse<LoadTestRunView> getRun(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID testId,
            @PathVariable UUID runId) {
        return ApiResponse.of(loadTestService.getRun(principal, testId, runId));
    }

    @PostMapping("/{testId}/runs/{runId}/stop")
    ApiResponse<LoadTestRunView> stop(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID testId,
            @PathVariable UUID runId) {
        return ApiResponse.of(loadTestService.stop(principal, testId, runId));
    }

    @PostMapping("/{testId}/runs/{runId}/cancel")
    ApiResponse<LoadTestRunView> cancel(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID testId,
            @PathVariable UUID runId) {
        return ApiResponse.of(loadTestService.cancel(principal, testId, runId));
    }

    @GetMapping("/{testId}/runs/{runId}/metrics")
    ApiResponse<List<LoadTestMetricView>> metrics(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID testId,
            @PathVariable UUID runId) {
        return ApiResponse.of(loadTestService.metrics(principal, testId, runId));
    }

    @GetMapping("/{testId}/runs/{runId}/results")
    ApiResponse<LoadTestResultView> results(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID testId,
            @PathVariable UUID runId) {
        return ApiResponse.of(loadTestService.results(principal, testId, runId));
    }
}
