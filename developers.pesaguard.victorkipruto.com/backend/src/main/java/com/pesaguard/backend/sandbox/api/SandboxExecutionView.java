package com.pesaguard.backend.sandbox.api;

import java.time.Instant;
import java.util.UUID;

import com.pesaguard.backend.environment.domain.EnvironmentType;
import com.pesaguard.backend.sandbox.domain.SandboxExecution;
import com.pesaguard.backend.sandbox.domain.SandboxExecutionKind;
import com.pesaguard.backend.sandbox.domain.SandboxExecutionOutcome;

/**
 * One recorded sandbox execution.
 *
 * <p>The environment type is returned explicitly so a client displaying a history
 * can show the guarantee rather than merely implying it.
 */
public record SandboxExecutionView(
        UUID id,
        UUID environmentId,
        EnvironmentType environmentType,
        SandboxExecutionKind kind,
        String method,
        String path,
        Integer statusCode,
        SandboxExecutionOutcome outcome,
        Integer durationMs,
        String responseExcerpt,
        Instant createdAt) {

    public static SandboxExecutionView from(SandboxExecution execution) {
        return new SandboxExecutionView(execution.getId(), execution.getEnvironmentId(),
                execution.getEnvironmentType(), execution.getKind(), execution.getMethod(),
                execution.getPath(), execution.getStatusCode(), execution.getOutcome(),
                execution.getDurationMs(), execution.getResponseExcerpt(),
                execution.getCreatedAt());
    }
}