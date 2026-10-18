package com.pesaguard.backend.credentials.api;

import java.time.Clock;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.pesaguard.backend.common.api.ApiResponse;
import com.pesaguard.backend.environment.domain.EnvironmentStatus;
import com.pesaguard.backend.environment.domain.EnvironmentType;
import com.pesaguard.backend.environment.infrastructure.ProjectEnvironmentRepository;
import com.pesaguard.backend.sandbox.domain.SandboxStatus;
import com.pesaguard.backend.sandbox.infrastructure.SandboxRepository;
import com.pesaguard.backend.securitycenter.application.SecurityEventService;
import com.pesaguard.backend.securitycenter.domain.SecurityEventType;

import jakarta.servlet.http.HttpServletRequest;

/**
 * A small, real sandbox API surface for validating a newly issued API key.
 * This route is deliberately fixed, read-only, and only available to credentials
 * bound to a SANDBOX environment with transactions:read.
 */
@RestController
@RequestMapping("/api/v1/sandbox")
public class SandboxApiController {

    private final ApiKeyAuthenticator authenticator;
    private final ProjectEnvironmentRepository environmentRepository;
    private final SandboxRepository sandboxRepository;
    private final Clock clock;
    private final SecurityEventService securityEvents;

    public SandboxApiController(
            ApiKeyAuthenticator authenticator,
            ProjectEnvironmentRepository environmentRepository,
            SandboxRepository sandboxRepository,
            Clock clock,
            SecurityEventService securityEvents) {
        this.authenticator = authenticator;
        this.environmentRepository = environmentRepository;
        this.sandboxRepository = sandboxRepository;
        this.clock = clock;
        this.securityEvents = securityEvents;
    }

    @GetMapping("/transactions")
    ApiResponse<SandboxTransactionsView> listTransactions(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            HttpServletRequest request) {
        ApiKey key = authenticator.authenticateKeyForRequest(bearerToken(authorization), request)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "A valid sandbox API key is required."));

        if (!key.scopeSet().contains("transactions:read")) {
            securityEvents.recordIfNew(key.getOrganizationId(), SecurityEventType.SCOPE_ABUSE,
                    key.getId(), "api_key",
                    "An API credential attempted an API operation outside its granted scopes.");
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "This API key does not have the transactions:read scope.");
        }
        var environment = environmentRepository.findByIdAndOrganizationIdAndProjectId(
                key.getEnvironmentId(), key.getOrganizationId(), key.getProjectId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "The API key environment is unavailable."));
        if (environment.getType() != EnvironmentType.SANDBOX
                || environment.getStatus() != EnvironmentStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "This endpoint only accepts keys bound to an active sandbox environment.");
        }
        boolean activeSandbox = sandboxRepository.findByEnvironmentId(environment.getId()).stream()
                .anyMatch(sandbox -> sandbox.getStatus() == SandboxStatus.ACTIVE
                        && sandbox.getExpiresAt().isAfter(clock.instant()));
        if (!activeSandbox) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "No active sandbox is available for this key.");
        }

        authenticator.recordUsage(key, request);
        SandboxTransactionsView result = new SandboxTransactionsView(
                java.util.List.of(), false, true, clock.instant());
        return ApiResponse.of(result);
    }

    private String bearerToken(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String token = authorization.substring(7).trim();
        return token.isEmpty() ? null : token;
    }
}
