package com.pesaguard.backend.sandbox.application;

import com.pesaguard.backend.sandbox.domain.SandboxIsolation;

/**
 * Something a sandbox can exercise.
 *
 * <p>The handler is handed the {@link SandboxIsolation} rather than an environment
 * id or a credential. That is the point: a handler that wants to reach outside the
 * sandbox has nothing to reach for. To touch production it would need a different
 * capability token, and {@link SandboxIsolation} has no method that produces one.
 *
 * <p>Handlers must honour the timeout they are given. It is already clamped to the
 * sandbox's own ceiling, so a handler cannot extend a hung call.
 */
@FunctionalInterface
public interface SandboxOperationHandler {

    /**
     * @param isolation the capability token; honour it
     * @param timeoutMs the maximum time this call may take, already clamped
     */
    SandboxOperationResult execute(SandboxIsolation isolation, int timeoutMs);
}