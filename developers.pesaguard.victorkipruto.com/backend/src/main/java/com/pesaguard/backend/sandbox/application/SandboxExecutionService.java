package com.pesaguard.backend.sandbox.application;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pesaguard.backend.common.api.RequestContext;
import com.pesaguard.backend.rbac.application.AuthorizationService;
import com.pesaguard.backend.rbac.domain.Permission;
import com.pesaguard.backend.sandbox.api.SandboxExecutionView;
import com.pesaguard.backend.sandbox.domain.SandboxExecution;
import com.pesaguard.backend.sandbox.domain.SandboxExecutionKind;
import com.pesaguard.backend.sandbox.domain.SandboxExecutionOutcome;
import com.pesaguard.backend.sandbox.domain.SandboxIsolation;
import com.pesaguard.backend.sandbox.domain.SandboxLimits;
import com.pesaguard.backend.sandbox.infrastructure.SandboxExecutionRepository;
import com.pesaguard.backend.sandbox.security.SandboxIsolationViolation;
import com.pesaguard.backend.security.principals.AuthenticatedUser;

/**
 * Runs test executions inside a sandbox.
 *
 * <p>Every entry point begins by obtaining a {@link SandboxIsolation} from
 * {@link SandboxService#requireExecutableIsolation}, and passes that token down. A
 * path that skipped this could not record an execution, because
 * {@link SandboxExecution#record} requires the token — there is no constructor
 * that takes a raw environment id.
 *
 * <p>Denials are recorded, not thrown at the caller. An isolation violation or an
 * exceeded quota is the expected result of a well-behaved sandbox, and the history
 * view is where an operator looks to see that a sandbox is being refused.
 */
@Service
public class SandboxExecutionService {

    private final SandboxService sandboxService;
    private final SandboxExecutionRepository executionRepository;
    private final AuthorizationService authorizationService;
    private final Clock clock;

    public SandboxExecutionService(
            SandboxService sandboxService,
            SandboxExecutionRepository executionRepository,
            AuthorizationService authorizationService,
            Clock clock) {
        this.sandboxService = sandboxService;
        this.executionRepository = executionRepository;
        this.authorizationService = authorizationService;
        this.clock = clock;
    }

/**
     * Runs one sandbox execution against a registered operation handler.
     *
     * <p>Exercising test requests, API flows, error responses, authentication,
     * webhooks and events all route through here; they differ only in the handler
     * and the recorded {@link SandboxExecutionKind}. One entry point means there is
     * one place where isolation is established, rather than six.
     */
    @Transactional
    public SandboxExecutionView execute(AuthenticatedUser principal, UUID sandboxId,
            SandboxExecutionKind kind, String method, String path, int requestBodyBytes,
            int requestedTimeoutMs, SandboxOperationHandler handler) {
        authorizationService.requirePermission(principal, Permission.SANDBOX_EXECUTE);
        SandboxIsolation isolation = sandboxService.requireExecutableIsolation(principal, sandboxId);
        SandboxLimits limits = sandboxService.limits(principal, sandboxId);

        java.time.Instant started = clock.instant();
        try {
            limits.requireRequestWithinLimit(requestBodyBytes);
        } catch (IllegalArgumentException exception) {
            return record(isolation, kind, method, path, null, SandboxExecutionOutcome.DENIED,
                    started, null, exception.getMessage(), principal);
        }

        // Clamped by the sandbox's own ceiling, so a caller cannot ask for a longer
        // hang than the sandbox permits.
        int timeoutMs = limits.effectiveTimeoutMs(requestedTimeoutMs);
        SandboxExecutionOutcome outcome;
        Integer statusCode = null;
        String responseExcerpt;
        try {
            SandboxOperationResult result = handler.execute(isolation, timeoutMs);
            statusCode = result.statusCode();
            responseExcerpt = result.body();
            outcome = result.truncated() ? SandboxExecutionOutcome.TRUNCATED
                    : SandboxExecutionOutcome.SUCCEEDED;
        } catch (SandboxIsolationViolation violation) {
            // DENIED, not FAILED: the guard did its job. Reporting this as an error
            // would make a well-isolated sandbox look broken.
            outcome = SandboxExecutionOutcome.DENIED;
            responseExcerpt = "Refused: the operation attempted to leave the sandbox.";
        } catch (RuntimeException failure) {
            outcome = SandboxExecutionOutcome.FAILED;
            responseExcerpt = failure.getClass().getSimpleName();
        }
        return record(isolation, kind, method, path, statusCode, outcome, started,
                requestBodyBytes > 0 ? requestBodyBytes + " bytes" : null,
                responseExcerpt, principal);
    }

    private SandboxExecutionView record(SandboxIsolation isolation, SandboxExecutionKind kind,
            String method, String path, Integer statusCode, SandboxExecutionOutcome outcome,
            java.time.Instant started, String requestExcerpt, String responseExcerpt,
            AuthenticatedUser principal) {
        int durationMs = (int) Math.max(0,
                clock.instant().toEpochMilli() - started.toEpochMilli());
        SandboxExecution execution = executionRepository.save(SandboxExecution.record(isolation,
                kind, method, path, statusCode, outcome, durationMs, requestExcerpt,
                truncate(responseExcerpt), principal.userId(),
                RequestContext.currentRequestId().toString()));
        return SandboxExecutionView.from(execution);
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 4096 ? value : value.substring(0, 4096);
    }

    @Transactional(readOnly = true)
    public List<SandboxExecutionView> history(AuthenticatedUser principal, UUID sandboxId, int limit) {
        authorizationService.requirePermission(principal, Permission.SANDBOX_READ);
        // Resolved for its tenant check: history must not be readable across tenants.
        sandboxService.get(principal, sandboxId);
        int capped = Math.max(1, Math.min(limit, 200));
        return executionRepository
                .findBySandboxIdOrderByCreatedAtDesc(sandboxId, PageRequest.of(0, capped))
                .stream().map(SandboxExecutionView::from).toList();
    }
}
