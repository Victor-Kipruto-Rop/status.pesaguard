package com.pesaguard.backend.analytics.api;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pesaguard.backend.analytics.application.RequestObservabilityService;
import com.pesaguard.backend.analytics.application.RequestObservabilityService.RequestLog;
import com.pesaguard.backend.analytics.application.UsageQueryService;
import com.pesaguard.backend.analytics.domain.UsageGranularity;
import com.pesaguard.backend.analytics.domain.UsageBucket;
import com.pesaguard.backend.common.api.PageResponse;
import com.pesaguard.backend.events.application.EventPlatformService;
import com.pesaguard.backend.events.application.EventPlatformService.DeliveryView;
import com.pesaguard.backend.organization.application.OrganizationLifecycleService;
import com.pesaguard.backend.organization.api.OrganizationView;
import com.pesaguard.backend.security.principals.AuthenticatedUser;

/** Bounded CSV exports backed by the same tenant and permission scoped queries as the portal. */
@RestController
@RequestMapping("/api/v1/exports")
public class UsageExportController {

    private static final int MAX_ROWS = 5_000;
    private static final int PAGE_SIZE = 100;

    private final UsageQueryService usageQueryService;
    private final RequestObservabilityService observabilityService;
    private final EventPlatformService eventPlatformService;
    private final OrganizationLifecycleService organizationLifecycleService;
    private final Clock clock;

    public UsageExportController(UsageQueryService usageQueryService,
            RequestObservabilityService observabilityService,
            EventPlatformService eventPlatformService,
            OrganizationLifecycleService organizationLifecycleService,
            Clock clock) {
        this.usageQueryService = usageQueryService;
        this.observabilityService = observabilityService;
        this.eventPlatformService = eventPlatformService;
        this.organizationLifecycleService = organizationLifecycleService;
        this.clock = clock;
    }

    @GetMapping(value = "/usage", produces = "text/csv")
    ResponseEntity<byte[]> usage(@AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) UsageGranularity granularity,
            @RequestParam(required = false) UUID projectId,
            @RequestParam(required = false) UUID environmentId,
            @RequestParam(required = false) UUID apiKeyId) {
        Instant exportTo = to == null ? clock.instant() : to;
        Instant exportFrom = from == null ? exportTo.minusSeconds(86_400) : from;
        List<String> rows = new ArrayList<>();
        rows.add(CsvExport.row("window_start", "granularity", "project_id", "environment_id",
                "endpoint", "method", "total_requests", "successful_requests", "failed_requests",
                "client_errors", "server_errors", "p50_latency_ms", "p95_latency_ms",
                "p99_latency_ms", "response_bytes"));
        int pageNumber = 0;
        int exported = 0;
        long total = Long.MAX_VALUE;
        while (exported < MAX_ROWS && exported < total) {
            Page<UsageBucket> page = usageQueryService.exportRows(principal, exportFrom, exportTo,
                    granularity, projectId, environmentId, apiKeyId, PageRequest.of(pageNumber++, PAGE_SIZE));
            total = page.getTotalElements();
            for (UsageBucket bucket : page.getContent()) {
                rows.add(CsvExport.row(bucket.getWindowStart(), bucket.getGranularity(),
                        bucket.getProjectId(), bucket.getEnvironmentId(), bucket.getEndpoint(),
                        bucket.getMethod(), bucket.getTotalRequests(), bucket.getSuccessfulRequests(),
                        bucket.getFailedRequests(), bucket.getClientErrors(), bucket.getServerErrors(),
                        bucket.getP50LatencyMs(), bucket.getP95LatencyMs(), bucket.getP99LatencyMs(),
                        bucket.getResponseBytesTotal()));
                exported++;
            }
            if (!page.hasNext() || page.getContent().isEmpty()) break;
        }
        return response("usage.csv", String.join("\r\n", rows) + "\r\n", total > exported);
    }

    @GetMapping(value = "/logs", produces = "text/csv")
    ResponseEntity<byte[]> logs(@AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(required = false) UUID projectId,
            @RequestParam(required = false) UUID environmentId,
            @RequestParam(required = false) Integer statusCode,
            @RequestParam(required = false) String method,
            @RequestParam(required = false) String requestId,
            @RequestParam(required = false) UUID apiKeyId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        Instant exportTo = to == null ? clock.instant() : to;
        Instant exportFrom = from == null ? exportTo.minusSeconds(7 * 86_400L) : from;
        List<String> rows = new ArrayList<>();
        rows.add(CsvExport.row("request_id", "project_id", "environment_id", "method",
                "endpoint", "status_code", "latency_ms", "response_bytes", "occurred_at",
                "recorded_at"));
        int pageNumber = 0;
        long total = Long.MAX_VALUE;
        int exported = 0;
        while (exported < MAX_ROWS && exported < total) {
            Page<RequestLog> page = observabilityService.search(principal, projectId,
                    environmentId, statusCode, method, requestId, apiKeyId, exportFrom, exportTo,
                    pageNumber++, PAGE_SIZE);
            total = page.getTotalElements();
            for (RequestLog log : page.getContent()) {
                rows.add(CsvExport.row(log.requestId(), log.projectId(), log.environmentId(),
                        log.method(), log.endpoint(), log.statusCode(), log.latencyMs(),
                        log.responseBytes(), log.occurredAt(), log.recordedAt()));
                exported++;
            }
            if (!page.hasNext() || page.getContent().isEmpty()) break;
        }
        boolean truncated = total > exported;
        return response("request-logs.csv", String.join("\r\n", rows) + "\r\n", truncated);
    }

    @GetMapping(value = "/webhook-deliveries", produces = "text/csv")
    ResponseEntity<byte[]> webhookDeliveries(@AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam UUID projectId, @RequestParam UUID environmentId) {
        List<String> rows = new ArrayList<>();
        rows.add(CsvExport.row("delivery_id", "event_id", "event_type", "subscription_id",
                "project_id", "environment_id", "endpoint_id", "attempt", "status",
                "response_code", "error_code", "latency_ms", "next_attempt_at", "created_at"));
        int pageNumber = 0;
        int exported = 0;
        long total = Long.MAX_VALUE;
        while (exported < MAX_ROWS && exported < total) {
            PageResponse<DeliveryView> page = eventPlatformService.deliveries(principal,
                    projectId, environmentId, pageNumber++, PAGE_SIZE);
            total = page.totalElements();
            for (DeliveryView delivery : page.items()) {
                rows.add(CsvExport.row(delivery.id(), delivery.eventId(), delivery.eventType(),
                        delivery.subscriptionId(), delivery.projectId(), delivery.environmentId(),
                        delivery.endpointId(), delivery.attempt(), delivery.status(),
                        delivery.responseCode(), delivery.errorCode(), delivery.latencyMs(),
                        delivery.nextAttemptAt(), delivery.createdAt()));
                exported++;
            }
            if (page.page() + 1 >= page.totalPages() || page.items().isEmpty()) break;
        }
        return response("webhook-deliveries.csv", String.join("\r\n", rows) + "\r\n",
                total > exported);
    }

    @GetMapping(value = "/organization", produces = "text/csv")
    ResponseEntity<byte[]> organization(@AuthenticationPrincipal AuthenticatedUser principal) {
        OrganizationView organization = organizationLifecycleService.current(principal);
        String csv = CsvExport.row("id", "name", "slug", "type", "status", "owner_user_id",
                "created_at", "updated_at") + "\r\n"
                + CsvExport.row(organization.id(), organization.name(), organization.slug(),
                        organization.type(), organization.status(), organization.ownerUserId(),
                        organization.createdAt(), organization.updatedAt()) + "\r\n";
        return response("organization.csv", csv, false);
    }

    private static ResponseEntity<byte[]> response(String filename, String csv, boolean truncated) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("text", "csv", StandardCharsets.UTF_8));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(filename, StandardCharsets.UTF_8).build());
        headers.setCacheControl("no-store");
        headers.set("X-Export-Truncated", Boolean.toString(truncated));
        return ResponseEntity.ok().headers(headers).body(csv.getBytes(StandardCharsets.UTF_8));
    }

    private static final class CsvExport {
        private CsvExport() {
        }

        static String row(Object... values) {
            return java.util.Arrays.stream(values).map(CsvExport::cell)
                    .collect(java.util.stream.Collectors.joining(","));
        }

        private static String cell(Object value) {
            if (value == null) return "";
            String text = String.valueOf(value);
            int first = 0;
            while (first < text.length() && Character.isWhitespace(text.charAt(first))) first++;
            if (first < text.length() && "=+-@".indexOf(text.charAt(first)) >= 0) {
                text = "'" + text;
            }
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
    }
}
