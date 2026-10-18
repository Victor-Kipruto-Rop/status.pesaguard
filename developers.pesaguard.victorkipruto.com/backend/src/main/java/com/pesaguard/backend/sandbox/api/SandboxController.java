package com.pesaguard.backend.sandbox.api;

import java.util.List;
import java.util.UUID;

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
import com.pesaguard.backend.sandbox.application.SandboxExecutionService;
import com.pesaguard.backend.sandbox.application.SandboxLimitsService;
import com.pesaguard.backend.sandbox.application.SandboxOperationResult;
import com.pesaguard.backend.sandbox.application.SandboxService;
import com.pesaguard.backend.sandbox.domain.SandboxExecutionKind;
import com.pesaguard.backend.security.principals.AuthenticatedUser;

import jakarta.validation.Valid;

/**
 * Sandbox lifecycle and execution history.
 *
 * <p>Every endpoint requires a portal session and a sandbox permission. There is
 * deliberately no endpoint that executes against an arbitrary target: execution
 * runs through {@link SandboxExecutionService}, which only ever holds a
 * {@link com.pesaguard.backend.sandbox.domain.SandboxIsolation} obtained from a
 * sandbox that was pinned at creation.
 */
@RestController
@RequestMapping("/api/v1/sandboxes")
public class SandboxController {

    private final SandboxService sandboxService;
    private final SandboxExecutionService executionService;
    private final SandboxLimitsService limitsService;

    public SandboxController(SandboxService sandboxService,
            SandboxExecutionService executionService, SandboxLimitsService limitsService) {
        this.sandboxService = sandboxService;
        this.executionService = executionService;
        this.limitsService = limitsService;
    }

    @PostMapping
    ApiResponse<SandboxView> create(@AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody CreateSandboxRequest request) {
        return ApiResponse.of(SandboxView.from(sandboxService.create(principal,
                request.environmentId(), request.name(), request.description(),
                request.ttlDuration())));
    }

    @GetMapping
    ApiResponse<List<SandboxView>> list(@AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(required = false) UUID projectId) {
        return ApiResponse.of(sandboxService.list(principal, projectId).stream()
                .map(SandboxView::from).toList());
    }

    @GetMapping("/{sandboxId}")
    ApiResponse<SandboxView> get(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID sandboxId) {
        return ApiResponse.of(SandboxView.from(sandboxService.get(principal, sandboxId)));
    }

    @PostMapping("/{sandboxId}/activate")
    ApiResponse<SandboxView> activate(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID sandboxId) {
        return ApiResponse.of(SandboxView.from(sandboxService.activate(principal, sandboxId)));
    }

    @PostMapping("/{sandboxId}/suspend")
    ApiResponse<SandboxView> suspend(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID sandboxId, @RequestParam(required = false) String reason) {
        return ApiResponse.of(SandboxView.from(sandboxService.suspend(principal, sandboxId, reason)));
    }

    @PostMapping("/{sandboxId}/resume")
    ApiResponse<SandboxView> resume(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID sandboxId) {
        return ApiResponse.of(SandboxView.from(sandboxService.resume(principal, sandboxId)));
    }

    /** Clears sandbox data. The lifecycle history is retained. */
    @PostMapping("/{sandboxId}/reset")
    ApiResponse<SandboxView> reset(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID sandboxId, @RequestParam(required = false) String reason) {
        return ApiResponse.of(SandboxView.from(sandboxService.reset(principal, sandboxId, reason)));
    }

    @DeleteMapping("/{sandboxId}")
    ApiResponse<SandboxView> delete(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID sandboxId, @RequestParam(required = false) String reason) {
        return ApiResponse.of(SandboxView.from(sandboxService.delete(principal, sandboxId, reason)));
    }

    @GetMapping("/{sandboxId}/executions")
    ApiResponse<List<SandboxExecutionView>> history(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID sandboxId,
            @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.of(executionService.history(principal, sandboxId, limit));
    }

    @PostMapping("/{sandboxId}/simulate")
    ApiResponse<SandboxExecutionView> simulate(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID sandboxId,
            @Valid @RequestBody RunSandboxScenarioRequest request) {
        SandboxScenario scenario = request.scenario();
        SandboxExecutionKind kind = scenario.kind();
        String body = scenario.resultBody(request.effectiveAmount(), request.effectiveCurrency());
        return ApiResponse.of(executionService.execute(principal, sandboxId, kind,
                scenario.method(), scenario.path(), body.length(), 1_000,
                (isolation, timeoutMs) -> SandboxOperationResult.status(scenario.statusCode(), body)));
    }

    @PutMapping("/{sandboxId}/limits")
    ApiResponse<SandboxLimitsView> updateLimits(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID sandboxId,
            @Valid @RequestBody UpdateSandboxLimitsRequest request) {
        return ApiResponse.of(SandboxLimitsView.from(limitsService.update(principal, sandboxId, request)));
    }

    @GetMapping("/{sandboxId}/limits")
    ApiResponse<SandboxLimitsView> limits(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID sandboxId) {
        return ApiResponse.of(SandboxLimitsView.from(sandboxService.limits(principal, sandboxId)));
    }
}