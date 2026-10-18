package com.pesaguard.backend.credentials.api;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.pesaguard.backend.analytics.application.UsageQueryService;
import com.pesaguard.backend.analytics.application.UsageQueryService.UsageSeries;
import com.pesaguard.backend.analytics.domain.UsageGranularity;
import com.pesaguard.backend.audit.domain.AuditEvent;
import com.pesaguard.backend.audit.infrastructure.AuditEventRepository;
import com.pesaguard.backend.common.api.ApiResponse;
import com.pesaguard.backend.common.api.PageResponse;
import com.pesaguard.backend.environment.domain.EnvironmentStatus;
import com.pesaguard.backend.environment.infrastructure.ProjectEnvironmentRepository;
import com.pesaguard.backend.events.application.EventPlatformService;
import com.pesaguard.backend.events.application.EventPlatformService.DeliveryView;
import com.pesaguard.backend.events.application.EventPlatformService.EventRecord;
import com.pesaguard.backend.events.application.EventPlatformService.EventTypeView;
import com.pesaguard.backend.events.application.EventPlatformService.SubscriptionView;
import com.pesaguard.backend.organization.domain.Organization;
import com.pesaguard.backend.organization.domain.OrganizationStatus;
import com.pesaguard.backend.organization.infrastructure.OrganizationRepository;
import com.pesaguard.backend.project.domain.ProjectStatus;
import com.pesaguard.backend.project.infrastructure.ProjectRepository;
import com.pesaguard.backend.rbac.domain.Permission;
import com.pesaguard.backend.security.principals.AuthenticatedUser;
import com.pesaguard.backend.securitycenter.application.SecurityEventService;
import com.pesaguard.backend.securitycenter.domain.SecurityEventType;
import com.pesaguard.backend.webhooks.application.WebhookEndpointService;
import com.pesaguard.backend.webhooks.application.WebhookEndpointService.EndpointView;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Read-only data routes for API keys. This is intentionally separate from
 * session-backed identity and organization-management controllers.
 */
@RestController
@RequestMapping("/api/v1/key-data")
public class ApiKeyDataController {

    private final ApiKeyAuthenticator authenticator;
    private final OrganizationRepository organizationRepository;
    private final ProjectRepository projectRepository;
    private final ProjectEnvironmentRepository environmentRepository;
    private final UsageQueryService usageQueryService;
    private final AuditEventRepository auditEventRepository;
    private final EventPlatformService eventPlatformService;
    private final WebhookEndpointService webhookEndpointService;
    private final SecurityEventService securityEvents;

    public ApiKeyDataController(ApiKeyAuthenticator authenticator,
            OrganizationRepository organizationRepository, ProjectRepository projectRepository,
            ProjectEnvironmentRepository environmentRepository, UsageQueryService usageQueryService,
            AuditEventRepository auditEventRepository, EventPlatformService eventPlatformService,
            WebhookEndpointService webhookEndpointService, SecurityEventService securityEvents) {
        this.authenticator = authenticator;
        this.organizationRepository = organizationRepository;
        this.projectRepository = projectRepository;
        this.environmentRepository = environmentRepository;
        this.usageQueryService = usageQueryService;
        this.auditEventRepository = auditEventRepository;
        this.eventPlatformService = eventPlatformService;
        this.webhookEndpointService = webhookEndpointService;
        this.securityEvents = securityEvents;
    }

    @GetMapping("/usage")
    ApiResponse<UsageSeries> usage(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) UsageGranularity granularity,
            HttpServletRequest request) {
        KeyAccess access = authenticate(request, "usage:read");
        UsageSeries result = usageQueryService.series(access.principal(), from, to, granularity,
                access.key().getProjectId(), access.key().getEnvironmentId());
        authenticator.recordUsage(access.key(), request);
        return ApiResponse.of(result);
    }

    @GetMapping("/organization")
    ApiResponse<OrganizationContext> organization(HttpServletRequest request) {
        KeyAccess access = authenticate(request, "organization:read");
        Organization organization = access.organization();
        authenticator.recordUsage(access.key(), request);
        return ApiResponse.of(new OrganizationContext(
                organization.getId(), organization.getName(), lower(organization.getStatus().name())));
    }

    @GetMapping("/project")
    ApiResponse<ProjectContext> project(HttpServletRequest request) {
        KeyAccess access = authenticate(request, "project:read");
        var environment = access.environment();
        authenticator.recordUsage(access.key(), request);
        return ApiResponse.of(new ProjectContext(
                access.project().getId(),
                access.project().getName(),
                access.project().getOrganizationId(),
                lower(access.project().getStatus().name()),
                new EnvironmentContext(environment.getId(), environment.getName(),
                        lower(environment.getStatus().name()), lower(environment.getType().name()), "v1"),
                "v1",
                access.project().getCreatedAt()));
    }

    @GetMapping("/environment")
    ApiResponse<EnvironmentContext> environment(HttpServletRequest request) {
        KeyAccess access = authenticate(request, "environment:read");
        var environment = access.environment();
        authenticator.recordUsage(access.key(), request);
        return ApiResponse.of(new EnvironmentContext(
                environment.getId(), environment.getName(), lower(environment.getStatus().name()),
                lower(environment.getType().name()), "v1"));
    }

    @GetMapping("/auth/context")
    ApiResponse<AuthenticationContext> authenticationContext(HttpServletRequest request) {
        KeyAccess access = authenticate(request, "api:read");
        var environment = access.environment();
        authenticator.recordUsage(access.key(), request);
        return ApiResponse.of(new AuthenticationContext(
                access.key().getOrganizationId(),
                access.key().getProjectId(),
                access.key().getEnvironmentId(),
                lower(environment.getType().name()),
                environment.getType() == com.pesaguard.backend.environment.domain.EnvironmentType.PRODUCTION
                        ? "live" : "test",
                lower(access.key().getStatus().name()),
                access.key().scopeSet().stream().sorted(Comparator.naturalOrder()).toList()));
    }

    @GetMapping("/audit-events")
    ApiResponse<PageResponse<KeyAuditEvent>> auditEvents(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            HttpServletRequest request) {
        KeyAccess access = authenticate(request, "audit:read");
        if (page < 0 || size < 1 || size > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Page must be non-negative and size must be between 1 and 100.");
        }
        Page<AuditEvent> result = auditEventRepository
                .findByOrganizationIdAndProjectIdOrderBySequenceNumberDesc(
                        access.key().getOrganizationId(), access.key().getProjectId(),
                        PageRequest.of(page, size));
        PageResponse<KeyAuditEvent> response = PageResponse.of(
                result.getContent().stream().map(KeyAuditEvent::from).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements());
        authenticator.recordUsage(access.key(), request);
        return ApiResponse.of(response);
    }

    @GetMapping("/events")
    ApiResponse<PageResponse<EventRecord>> events(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            HttpServletRequest request) {
        KeyAccess access = authenticate(request, "events:read");
        PageResponse<EventRecord> result = eventPlatformService.events(
                access.principal(), access.key().getProjectId(), access.key().getEnvironmentId(),
                page, size);
        authenticator.recordUsage(access.key(), request);
        return ApiResponse.of(result);
    }

    @GetMapping("/events/catalog")
    ApiResponse<List<EventTypeView>> eventCatalog(HttpServletRequest request) {
        KeyAccess access = authenticate(request, "events:read");
        List<EventTypeView> result = eventPlatformService.catalog(access.principal());
        authenticator.recordUsage(access.key(), request);
        return ApiResponse.of(result);
    }

    @GetMapping("/events/subscriptions")
    ApiResponse<PageResponse<SubscriptionView>> subscriptions(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            HttpServletRequest request) {
        KeyAccess access = authenticate(request, "events:read");
        PageResponse<SubscriptionView> result = eventPlatformService.subscriptions(
                access.principal(), access.key().getProjectId(), access.key().getEnvironmentId(), page, size);
        authenticator.recordUsage(access.key(), request);
        return ApiResponse.of(result);
    }

    @GetMapping("/events/deliveries")
    ApiResponse<PageResponse<DeliveryView>> deliveries(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            HttpServletRequest request) {
        KeyAccess access = authenticate(request, "events:read");
        PageResponse<DeliveryView> result = eventPlatformService.deliveries(
                access.principal(), access.key().getProjectId(), access.key().getEnvironmentId(),
                page, size);
        authenticator.recordUsage(access.key(), request);
        return ApiResponse.of(result);
    }

    @GetMapping("/webhooks")
    ApiResponse<List<EndpointView>> webhooks(HttpServletRequest request) {
        KeyAccess access = authenticate(request, "webhooks:read");
        List<EndpointView> result = webhookEndpointService.list(
                access.principal(), access.key().getProjectId(), access.key().getEnvironmentId());
        authenticator.recordUsage(access.key(), request);
        return ApiResponse.of(result);
    }

    private KeyAccess authenticate(HttpServletRequest request, String requiredScope) {
        String authorization = request.getHeader("Authorization");
        ApiKey key = authenticator.authenticateKeyForRequest(bearerToken(authorization), request)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "A valid API key is required."));
        if (!key.scopeSet().contains(requiredScope)) {
            securityEvents.recordIfNew(key.getOrganizationId(), SecurityEventType.SCOPE_ABUSE,
                    key.getId(), "api_key",
                    "An API credential attempted an API operation outside its granted scopes.");
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "This API key does not have the " + requiredScope + " scope.");
        }
        Organization organization = organizationRepository.findById(key.getOrganizationId())
                .filter(value -> value.getStatus() == OrganizationStatus.ACTIVE).orElse(null);
        var project = projectRepository.findByIdAndOrganizationId(key.getProjectId(), key.getOrganizationId())
                .filter(value -> value.getStatus() == ProjectStatus.ACTIVE).orElse(null);
        var environment = environmentRepository.findByIdAndOrganizationIdAndProjectId(
                        key.getEnvironmentId(), key.getOrganizationId(), key.getProjectId())
                .filter(value -> value.getStatus() == EnvironmentStatus.ACTIVE).orElse(null);
        if (organization == null || project == null || environment == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "The API key's organization, project, or environment is unavailable.");
        }

        String permission = switch (requiredScope) {
            case "usage:read" -> Permission.USAGE_READ.value();
            case "audit:read" -> Permission.AUDIT_READ.value();
            case "events:read", "webhooks:read" -> Permission.WEBHOOK_READ.value();
            case "organization:read", "project:read", "environment:read" -> "context:read";
            case "api:read" -> "api:read";
            default -> throw new IllegalArgumentException("Unsupported API key data scope.");
        };
        Set<String> permissions = Set.of(permission);
        AuthenticatedUser principal = new AuthenticatedUser(
                key.getCreatedBy(), key.getOrganizationId(), null, "", key.getName(),
                permissions, OrganizationStatus.ACTIVE, true, permissions);
        return new KeyAccess(key, principal, organization, project, environment);
    }

    private static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    private static String bearerToken(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String token = authorization.substring(7).trim();
        return token.isEmpty() ? null : token;
    }

    private record KeyAccess(ApiKey key, AuthenticatedUser principal, Organization organization,
            com.pesaguard.backend.project.domain.Project project,
            com.pesaguard.backend.environment.domain.ProjectEnvironment environment) {
    }

    public record OrganizationContext(UUID id, String name, String status) {
    }

    public record ProjectContext(UUID id, String name, UUID organizationId, String status,
            EnvironmentContext environment, String apiVersion, Instant createdAt) {
    }

    public record EnvironmentContext(UUID id, String name, String status, String type, String apiVersion) {
    }

    public record AuthenticationContext(
            @JsonProperty("organization_id") UUID organizationId,
            @JsonProperty("project_id") UUID projectId,
            @JsonProperty("environment_id") UUID environmentId,
            String environment,
            @JsonProperty("key_type") String keyType,
            String status,
            List<String> scopes) {
    }

    private record KeyAuditEvent(
            UUID id,
            long sequenceNumber,
            String action,
            String resourceType,
            String resourceId,
            UUID projectId,
            String metadata,
            Instant createdAt) {
        static KeyAuditEvent from(AuditEvent event) {
            return new KeyAuditEvent(event.getId(), event.getSequenceNumber(), event.getAction(),
                    event.getResourceType(), event.getResourceId(), event.getProjectId(),
                    event.getMetadata(), event.getCreatedAt());
        }
    }
}
