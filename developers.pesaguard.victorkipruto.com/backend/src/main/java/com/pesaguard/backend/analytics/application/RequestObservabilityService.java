package com.pesaguard.backend.analytics.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pesaguard.backend.analytics.domain.ApiRequestEvent;
import com.pesaguard.backend.analytics.infrastructure.ApiRequestEventRepository;
import com.pesaguard.backend.credentials.api.ApiKeyRepository;
import com.pesaguard.backend.common.exception.BusinessException;
import com.pesaguard.backend.common.exception.ResourceNotFoundException;
import com.pesaguard.backend.common.exception.UnauthorizedException;
import com.pesaguard.backend.project.application.ProjectAccessScope;
import com.pesaguard.backend.rbac.application.AuthorizationService;
import com.pesaguard.backend.rbac.domain.Permission;
import com.pesaguard.backend.security.principals.AuthenticatedUser;

@Service
public class RequestObservabilityService {

    private static final Duration MAX_RANGE = Duration.ofDays(31);
    private static final int MAX_PAGE_SIZE = 100;

    private final ApiRequestEventRepository requestRepository;
    private final Clock clock;
    private final ProjectAccessScope projectAccessScope;
    private final AuthorizationService authorizationService;
    private final ApiKeyRepository apiKeyRepository;

    public RequestObservabilityService(ApiRequestEventRepository requestRepository, Clock clock,
            ProjectAccessScope projectAccessScope, AuthorizationService authorizationService,
            ApiKeyRepository apiKeyRepository) {
        this.requestRepository = requestRepository;
        this.clock = clock;
        this.projectAccessScope = projectAccessScope;
        this.authorizationService = authorizationService;
        this.apiKeyRepository = apiKeyRepository;
    }

    @Transactional(readOnly = true)
    public Page<RequestLog> search(AuthenticatedUser principal, UUID projectId,
            UUID environmentId, Integer statusCode, String method, String requestId,
            Instant from, Instant to, int page, int size) {
        return search(principal, projectId, environmentId, statusCode, method, requestId,
                null, from, to, page, size);
    }

    @Transactional(readOnly = true)
    public Page<RequestLog> search(AuthenticatedUser principal, UUID projectId,
            UUID environmentId, Integer statusCode, String method, String requestId,
            UUID apiKeyId, Instant from, Instant to, int page, int size) {
        requireUsageRead(principal);
        requireKeyScope(principal, projectId, environmentId, apiKeyId);
        Instant end = to == null ? clock.instant() : to;
        Instant start = from == null ? end.minus(Duration.ofDays(7)) : from;
        Duration range = Duration.between(start, end);
        if (range.isNegative() || range.isZero() || range.compareTo(MAX_RANGE) > 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "REQUEST_LOG_RANGE_INVALID",
                    "Choose a time range greater than zero and no longer than 31 days.");
        }
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "REQUEST_LOG_PAGE_INVALID",
                    "Page must be non-negative and page size must be between 1 and 100.");
        }
        if (statusCode != null && (statusCode < 100 || statusCode > 599)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "REQUEST_LOG_STATUS_INVALID",
                    "HTTP status code must be between 100 and 599.");
        }
        String normalizedMethod = method == null || method.isBlank()
                ? null : method.trim().toUpperCase(java.util.Locale.ROOT);
        if (normalizedMethod != null && !normalizedMethod.matches("[A-Z]{1,8}")) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "REQUEST_LOG_METHOD_INVALID",
                    "HTTP method filter is invalid.");
        }
        String normalizedRequestId = requestId == null || requestId.isBlank()
                ? null : requestId.trim();
        ProjectAccessScope.Scope scope = projectAccessScope.resolve(principal, projectId, environmentId);
        if (scope.isEmpty()) {
            return Page.empty(PageRequest.of(page, size));
        }
        Page<ApiRequestEvent> result = scope.projectIds() == null
                ? requestRepository.searchOrganizationRequests(
                        principal.organizationId(), scope.projectId(), scope.environmentId(), statusCode,
                        normalizedMethod, normalizedRequestId, apiKeyId, start, end, PageRequest.of(page, size))
                : requestRepository.searchOrganizationRequestsForProjects(
                        principal.organizationId(), scope.projectIds(), scope.projectId(), scope.environmentId(),
                        statusCode, normalizedMethod, normalizedRequestId, apiKeyId, start, end, PageRequest.of(page, size));
        return result
                .map(RequestLog::from);
    }

    @Transactional(readOnly = true)
    public RequestLog find(AuthenticatedUser principal, String requestId) {
        return find(principal, requestId, null, null);
    }

    @Transactional(readOnly = true)
    public RequestLog find(AuthenticatedUser principal, String requestId,
            UUID projectId, UUID environmentId) {
        return find(principal, requestId, projectId, environmentId, null);
    }

    @Transactional(readOnly = true)
    public RequestLog find(AuthenticatedUser principal, String requestId,
            UUID projectId, UUID environmentId, UUID apiKeyId) {
        requireUsageRead(principal);
        requireKeyScope(principal, projectId, environmentId, apiKeyId);
        if (requestId == null || requestId.isBlank() || requestId.length() > 64) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "REQUEST_ID_INVALID",
                    "A valid request ID is required.");
        }
        ApiRequestEvent event = requestRepository.findByRequestIdAndOrganizationId(
                        requestId.trim(), principal.organizationId())
                .orElseThrow(() -> requestNotFound());
        ProjectAccessScope.Scope scope = projectAccessScope.resolve(principal, projectId, environmentId);
        if (scope.projectIds() != null
                && (event.getProjectId() == null || !scope.projectIds().contains(event.getProjectId()))) {
            throw requestNotFound();
        }
        if ((scope.projectId() != null && !scope.projectId().equals(event.getProjectId()))
                || (scope.environmentId() != null && !scope.environmentId().equals(event.getEnvironmentId()))) {
            throw requestNotFound();
        }
        if (apiKeyId != null && !apiKeyId.equals(event.getApiKeyId())) {
            throw requestNotFound();
        }
        return RequestLog.from(event);
    }

    private void requireUsageRead(AuthenticatedUser principal) {
        if (principal == null) {
            throw new UnauthorizedException("USAGE_UNAUTHENTICATED",
                    "Authentication is required.");
        }
        if (!authorizationService.hasPermission(principal, Permission.USAGE_READ)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "USAGE_FORBIDDEN",
                    "You do not have permission to read request logs.");
        }
    }

    private void requireKeyScope(AuthenticatedUser principal, UUID projectId,
            UUID environmentId, UUID apiKeyId) {
        if (apiKeyId == null) return;
        if (projectId == null || environmentId == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "REQUEST_LOG_KEY_SCOPE_REQUIRED",
                    "Project and environment are required when filtering request logs by API key.");
        }
        authorizationService.requirePermission(principal, Permission.CREDENTIAL_READ);
        if (!apiKeyRepository.existsByIdAndOrganizationIdAndProjectIdAndEnvironmentId(
                apiKeyId, principal.organizationId(), projectId, environmentId)) {
            throw new ResourceNotFoundException("API key");
        }
    }

    private BusinessException requestNotFound() {
        return new BusinessException(HttpStatus.NOT_FOUND,
                "REQUEST_LOG_NOT_FOUND", "No request log was found for that request ID.");
    }

    public record RequestLog(
            String requestId,
            UUID projectId,
            UUID environmentId,
            String endpoint,
            String method,
            int statusCode,
            int latencyMs,
            Long responseBytes,
            Instant occurredAt,
            Instant recordedAt) {

        static RequestLog from(ApiRequestEvent event) {
            return new RequestLog(event.getRequestId(), event.getProjectId(),
                    event.getEnvironmentId(), event.getEndpoint(), event.getMethod(),
                    event.getStatusCode(), event.getLatencyMs(), event.getResponseBytes(),
                    event.getOccurredAt(), event.getRecordedAt());
        }
    }
}
