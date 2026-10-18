package com.pesaguard.backend.analytics.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pesaguard.backend.analytics.application.UsageQueryService;
import com.pesaguard.backend.analytics.application.UsageQueryService.UsageSeries;
import com.pesaguard.backend.analytics.application.RequestObservabilityService;
import com.pesaguard.backend.analytics.application.RequestObservabilityService.RequestLog;
import com.pesaguard.backend.analytics.domain.UsageGranularity;
import com.pesaguard.backend.common.api.ApiResponse;
import com.pesaguard.backend.security.principals.AuthenticatedUser;

/**
 * Usage analytics for the portal.
 *
 * <p>Read-only. There is deliberately no endpoint to write, edit, or delete usage
 * data: the only correct source of these numbers is the request stream itself,
 * and any write path would be a way to falsify a customer's usage.
 *
 * <p>Every route requires a portal session and {@code usage:read}. The
 * organization is taken from the authenticated principal, never from a parameter,
 * so there is no organization argument that could be pointed at another tenant.
 */
@RestController
@RequestMapping("/api/v1/usage")
public class UsageController {

    private final UsageQueryService queryService;
    private final RequestObservabilityService observabilityService;

    public UsageController(UsageQueryService queryService,
            RequestObservabilityService observabilityService) {
        this.queryService = queryService;
        this.observabilityService = observabilityService;
    }

    /**
     * Usage over a time range, as a series plus totals.
     *
     * <p>Granularity defaults to whatever suits the requested span, and the
     * response states which was used so a caller never has to guess whether they
     * are reading minutes or days.
     */
    @GetMapping
    ApiResponse<UsageSeries> series(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) UsageGranularity granularity,
            @RequestParam(required = false) UUID projectId,
            @RequestParam(required = false) UUID environmentId,
            @RequestParam(required = false) UUID apiKeyId) {
        return ApiResponse.of(queryService.series(principal, from, to, granularity,
                projectId, environmentId, apiKeyId));
    }

    /** Which endpoints this organization called, for a usage breakdown. */
    @GetMapping("/endpoints")
    ApiResponse<List<String>> endpoints(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "HOUR") UsageGranularity granularity,
            @RequestParam(required = false) UUID projectId,
            @RequestParam(required = false) UUID environmentId,
            @RequestParam(required = false) UUID apiKeyId) {
        return ApiResponse.of(queryService.endpoints(principal, from, to, granularity,
                projectId, environmentId, apiKeyId));
    }

    /** Search real request telemetry. Bodies, credentials, and headers are not retained or exposed. */
    @GetMapping("/requests")
    ApiResponse<Page<RequestLog>> requests(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(required = false) UUID projectId,
            @RequestParam(required = false) UUID environmentId,
            @RequestParam(required = false) Integer statusCode,
            @RequestParam(required = false) String method,
            @RequestParam(required = false) String requestId,
            @RequestParam(required = false) UUID apiKeyId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.of(observabilityService.search(principal, projectId,
                environmentId, statusCode, method, requestId, apiKeyId, from, to, page, size));
    }

    /** A request-level correlation view, not a distributed span trace. */
    @GetMapping("/requests/{requestId}")
    ApiResponse<RequestLog> request(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @org.springframework.web.bind.annotation.PathVariable String requestId,
            @RequestParam(required = false) UUID projectId,
            @RequestParam(required = false) UUID environmentId,
            @RequestParam(required = false) UUID apiKeyId) {
        return ApiResponse.of(observabilityService.find(principal, requestId, projectId, environmentId, apiKeyId));
    }
}
