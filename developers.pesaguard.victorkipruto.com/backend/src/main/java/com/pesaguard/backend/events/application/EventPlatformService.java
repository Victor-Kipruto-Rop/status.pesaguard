package com.pesaguard.backend.events.application;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pesaguard.backend.common.exception.BusinessException;
import com.pesaguard.backend.common.exception.UnauthorizedException;
import com.pesaguard.backend.common.api.PageResponse;
import com.pesaguard.backend.environment.infrastructure.ProjectEnvironmentRepository;
import com.pesaguard.backend.events.domain.DeliveryStatus;
import com.pesaguard.backend.events.domain.EventDelivery;
import com.pesaguard.backend.events.domain.EventLifecycle;
import com.pesaguard.backend.events.domain.EventSubscription;
import com.pesaguard.backend.events.domain.EventType;
import com.pesaguard.backend.events.domain.EventTypeDefinition;
import com.pesaguard.backend.events.domain.SubscriptionStatus;
import com.pesaguard.backend.events.infrastructure.EventDeliveryRepository;
import com.pesaguard.backend.events.infrastructure.EventSubscriptionRepository;
import com.pesaguard.backend.events.infrastructure.EventTypeDefinitionRepository;
import com.pesaguard.backend.outbox.domain.OutboxEvent;
import com.pesaguard.backend.outbox.infrastructure.OutboxEventRepository;
import com.pesaguard.backend.project.infrastructure.ProjectRepository;
import com.pesaguard.backend.project.application.ProjectAccessScope;
import com.pesaguard.backend.environment.domain.EnvironmentPermission;
import com.pesaguard.backend.project.application.ProjectAuthorization;
import com.pesaguard.backend.rbac.application.AuthorizationService;
import com.pesaguard.backend.rbac.domain.Permission;
import com.pesaguard.backend.security.principals.AuthenticatedUser;
import com.pesaguard.backend.webhooks.application.WebhookEndpointService;
import com.pesaguard.backend.webhooks.application.WebhookDeliveryScheduler;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Service
public class EventPlatformService {

    private final EventTypeDefinitionRepository typeRepository;
    private final EventSubscriptionRepository subscriptionRepository;
    private final EventDeliveryRepository deliveryRepository;
    private final OutboxEventRepository outboxRepository;
    private final ProjectRepository projectRepository;
    private final ProjectEnvironmentRepository environmentRepository;
    private final WebhookEndpointService endpointService;
    private final WebhookDeliveryScheduler deliveryScheduler;
    private final Clock clock;
    private final AuthorizationService authorizationService;
    private final ProjectAccessScope projectAccessScope;
    private final ProjectAuthorization projectAuthorization;

    public EventPlatformService(EventTypeDefinitionRepository typeRepository,
            EventSubscriptionRepository subscriptionRepository,
            EventDeliveryRepository deliveryRepository, OutboxEventRepository outboxRepository,
            ProjectRepository projectRepository, ProjectEnvironmentRepository environmentRepository,
            WebhookEndpointService endpointService, WebhookDeliveryScheduler deliveryScheduler,
            Clock clock, AuthorizationService authorizationService,
            ProjectAccessScope projectAccessScope, ProjectAuthorization projectAuthorization) {
        this.typeRepository = typeRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.deliveryRepository = deliveryRepository;
        this.outboxRepository = outboxRepository;
        this.projectRepository = projectRepository;
        this.environmentRepository = environmentRepository;
        this.endpointService = endpointService;
        this.deliveryScheduler = deliveryScheduler;
        this.clock = clock;
        this.authorizationService = authorizationService;
        this.projectAccessScope = projectAccessScope;
        this.projectAuthorization = projectAuthorization;
    }

    @Transactional(readOnly = true)
    public List<EventTypeView> catalog(AuthenticatedUser principal) {
        require(principal, Permission.WEBHOOK_READ);
        return typeRepository.findByLifecycleInOrderByNameAsc(
                        List.of(EventLifecycle.ACTIVE, EventLifecycle.DEPRECATED))
                .stream().map(EventTypeView::from).toList();
    }

    @Transactional(readOnly = true)
    public PageResponse<EventRecord> events(AuthenticatedUser principal, UUID projectId,
            UUID environmentId, int page, int size) {
        require(principal, Permission.WEBHOOK_READ);
        validatePaging(page, size);
        requireEnvironment(principal, projectId, environmentId);
        projectAccessScope.resolve(principal, projectId, environmentId);
        Page<OutboxEvent> events = outboxRepository
                .findByOrganizationIdAndProjectIdAndEnvironmentIdOrderByCreatedAtDesc(
                        principal.organizationId(), projectId, environmentId, PageRequest.of(page, size));
        return PageResponse.of(events.getContent().stream().map(EventRecord::from).toList(),
                events.getNumber(), events.getSize(), events.getTotalElements());
    }

    @Transactional(readOnly = true)
    public PageResponse<SubscriptionView> subscriptions(AuthenticatedUser principal,
            UUID projectId, UUID environmentId, int page, int size) {
        require(principal, Permission.WEBHOOK_READ);
        validatePaging(page, size);
        requireEnvironment(principal, projectId, environmentId);
        projectAccessScope.resolve(principal, projectId, environmentId);
        Page<EventSubscription> subscriptions = subscriptionRepository
                .findByOrganizationIdAndProjectIdAndEnvironmentIdOrderByCreatedAtDesc(
                        principal.organizationId(), projectId, environmentId, PageRequest.of(page, size));
        return PageResponse.of(subscriptions.getContent().stream().map(SubscriptionView::from).toList(),
                subscriptions.getNumber(), subscriptions.getSize(), subscriptions.getTotalElements());
    }

    @Transactional
    public SubscriptionView createSubscription(AuthenticatedUser principal,
            CreateSubscription request) {
        require(principal, Permission.WEBHOOK_CREATE);
        if (request.projectId() == null || projectRepository.findByIdAndOrganizationId(
                request.projectId(), principal.organizationId()).isEmpty()) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND",
                    "Project was not found in this organization.");
        }
        projectAuthorization.requireProjectManage(principal, request.projectId());
        requireEnvironment(principal, request.projectId(), request.environmentId());
        projectAccessScope.resolve(principal, request.projectId(), request.environmentId());
        projectAccessScope.requireEnvironmentAccess(principal, request.projectId(), request.environmentId(),
                EnvironmentPermission.WRITE);
        EventType parsed = EventType.tryParse(request.eventType()).orElseThrow(() ->
                new BusinessException(HttpStatus.BAD_REQUEST, "EVENT_TYPE_INVALID",
                        "The event type is invalid."));
        EventTypeDefinition definition = typeRepository.findById(parsed.value())
                .filter(type -> type.shouldEmit(clock.instant()))
                .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST,
                        "EVENT_TYPE_UNAVAILABLE", "This event type is not available for subscription."));
        var endpoint = endpointService.findActiveEndpoint(principal.organizationId(),
                request.projectId(), request.environmentId(), request.endpointId().toString())
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND,
                        "WEBHOOK_NOT_FOUND",
                        "An active webhook endpoint was not found in this project environment."));
        try {
            EventSubscription.validateFilters(request.filters());
            EventSubscription subscription = subscriptionRepository.save(EventSubscription.create(
                    principal.organizationId(), request.projectId(), request.environmentId(),
                    endpoint.getId().toString(), parsed, definition.getVersion(),
                    request.description(), request.filters()));
            return SubscriptionView.from(subscription);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "EVENT_FILTER_INVALID",
                    exception.getMessage());
        }
    }

    @Transactional
    public SubscriptionView changeSubscriptionStatus(AuthenticatedUser principal,
            UUID subscriptionId, UUID environmentId, String requestedStatus) {
        require(principal, Permission.WEBHOOK_UPDATE);
        EventSubscription subscription = subscriptionRepository.findById(subscriptionId)
                .filter(existing -> existing.getOrganizationId().equals(principal.organizationId()))
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND,
                        "EVENT_SUBSCRIPTION_NOT_FOUND", "Event subscription was not found."));
        if (environmentId == null || !subscription.getEnvironmentId().equals(environmentId)) {
            throw new BusinessException(HttpStatus.NOT_FOUND,
                    "EVENT_SUBSCRIPTION_NOT_FOUND", "Event subscription was not found.");
        }
        projectAuthorization.requireProjectManage(principal, subscription.getProjectId());
        projectAccessScope.resolve(principal, subscription.getProjectId(), subscription.getEnvironmentId());
        projectAccessScope.requireEnvironmentAccess(principal, subscription.getProjectId(),
                subscription.getEnvironmentId(), EnvironmentPermission.WRITE);
        try {
            switch (requestedStatus == null ? "" : requestedStatus.toUpperCase(java.util.Locale.ROOT)) {
                case "ACTIVE" -> subscription.resume();
                case "SUSPENDED" -> subscription.suspend();
                case "CANCELLED" -> subscription.cancel();
                default -> throw new BusinessException(HttpStatus.BAD_REQUEST,
                        "EVENT_SUBSCRIPTION_STATUS_INVALID",
                        "Status must be ACTIVE, SUSPENDED, or CANCELLED.");
            }
        } catch (IllegalStateException exception) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    "EVENT_SUBSCRIPTION_STATUS_CONFLICT", exception.getMessage());
        }
        return SubscriptionView.from(subscription);
    }

    @Transactional(readOnly = true)
    public PageResponse<DeliveryView> deliveries(AuthenticatedUser principal, UUID projectId,
            UUID environmentId, int page, int size) {
        require(principal, Permission.WEBHOOK_READ);
        validatePaging(page, size);
        requireEnvironment(principal, projectId, environmentId);
        projectAccessScope.resolve(principal, projectId, environmentId);
        Page<EventDelivery> deliveries = deliveryRepository
                .findByOrganizationIdAndProjectIdAndEnvironmentIdOrderByCreatedAtDesc(
                        principal.organizationId(), projectId, environmentId, PageRequest.of(page, size));
        return PageResponse.of(deliveries.getContent().stream().map(DeliveryView::from).toList(),
                deliveries.getNumber(), deliveries.getSize(), deliveries.getTotalElements());
    }

    public DeliveryView replayDelivery(AuthenticatedUser principal, UUID deliveryId,
            UUID environmentId) {
        require(principal, Permission.WEBHOOK_UPDATE);
        EventDelivery failedDelivery = deliveryRepository.findByIdAndOrganizationId(
                        deliveryId, principal.organizationId())
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND,
                        "EVENT_DELIVERY_NOT_FOUND", "Webhook delivery was not found."));
        if (environmentId == null || !failedDelivery.getEnvironmentId().equals(environmentId)) {
            throw new BusinessException(HttpStatus.NOT_FOUND,
                    "EVENT_DELIVERY_NOT_FOUND", "Webhook delivery was not found.");
        }
        projectAuthorization.requireProjectManage(principal, failedDelivery.getProjectId());
        projectAccessScope.resolve(principal, failedDelivery.getProjectId(), failedDelivery.getEnvironmentId());
        projectAccessScope.requireEnvironmentAccess(principal, failedDelivery.getProjectId(),
                failedDelivery.getEnvironmentId(), EnvironmentPermission.WRITE);
        EventDelivery latest = deliveryRepository
                .findTopByEventIdAndSubscriptionIdOrderByAttemptDesc(
                        failedDelivery.getEventId(), failedDelivery.getSubscriptionId())
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND,
                        "EVENT_DELIVERY_NOT_FOUND", "Webhook delivery was not found."));
        if (!latest.getId().equals(deliveryId)
                || (latest.getStatus() != DeliveryStatus.DEAD_LETTERED
                        && latest.getStatus() != DeliveryStatus.FAILED)) {
            throw new BusinessException(HttpStatus.CONFLICT, "EVENT_DELIVERY_NOT_REPLAYABLE",
                    "Only the latest failed or dead-lettered delivery can be replayed.");
        }
        EventSubscription subscription = subscriptionRepository.findByIdAndOrganizationId(
                        latest.getSubscriptionId(), principal.organizationId())
                .filter(EventSubscription::delivers)
                .orElseThrow(() -> new BusinessException(HttpStatus.CONFLICT,
                        "EVENT_SUBSCRIPTION_NOT_ACTIVE",
                        "Resume the event subscription before replaying this delivery."));
        OutboxEvent event = outboxRepository.findByEventId(latest.getEventId())
                .filter(stored -> principal.organizationId().equals(stored.getOrganizationId()))
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND,
                        "EVENT_NOT_FOUND", "The source event is no longer available."));
        if (!deliveryScheduler.dispatchNow(event, subscription)) {
            throw new BusinessException(HttpStatus.CONFLICT, "EVENT_DELIVERY_NOT_REPLAYED",
                    "The endpoint or subscription is inactive, the event no longer matches its filters, or another worker is delivering it.");
        }
        return deliveryRepository.findTopByEventIdAndSubscriptionIdOrderByAttemptDesc(
                        event.getEventId(), subscription.getId())
                .map(DeliveryView::from)
                .orElseThrow(() -> new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "EVENT_REPLAY_NOT_RECORDED",
                        "The replay attempt was not recorded."));
    }

    private static void validatePaging(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "EVENT_PAGE_INVALID",
                    "Page must be non-negative and size must be between 1 and 100.");
        }
    }

    private void require(AuthenticatedUser principal, Permission permission) {
        if (principal == null) {
            throw new UnauthorizedException("EVENT_UNAUTHENTICATED",
                    "Authentication is required.");
        }
        if (!authorizationService.hasPermission(principal, permission)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "EVENT_FORBIDDEN",
                    "You do not have permission to access event data.");
        }
    }

    private void requireEnvironment(AuthenticatedUser principal, UUID projectId, UUID environmentId) {
        if (projectId == null || environmentId == null
                || environmentRepository.findByIdAndOrganizationIdAndProjectId(
                        environmentId, principal.organizationId(), projectId).isEmpty()) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "ENVIRONMENT_NOT_FOUND",
                    "Environment was not found in this project.");
        }
    }

    public record CreateSubscription(
            @NotNull UUID projectId,
            @NotNull UUID environmentId,
            @NotNull UUID endpointId,
            @NotBlank @Size(max = 128) String eventType,
            @Size(max = 500) String description,
            Map<String, String> filters) {
    }

    public record EventTypeView(String name, String description, String category,
            int version, String schema, String lifecycle) {
        static EventTypeView from(EventTypeDefinition definition) {
            return new EventTypeView(definition.getName(), definition.getDescription(),
                    definition.getCategory().name(), definition.getVersion(),
                    definition.getSchema(), definition.getLifecycle().name());
        }
    }

    public record EventRecord(UUID id, UUID eventId, String eventType, int version,
            UUID projectId, UUID environmentId, String correlationId, String traceId, String status,
            int attemptCount, java.time.Instant createdAt, java.time.Instant publishedAt) {
        static EventRecord from(OutboxEvent event) {
            return new EventRecord(event.getId(), event.getEventId(), event.getEventType(),
                    event.getEventVersion(), event.getProjectId(), event.getEnvironmentId(),
                    event.getCorrelationId(),
                    event.getTraceId(), event.getStatus().name(), event.getAttemptCount(),
                    event.getCreatedAt(), event.getPublishedAt());
        }
    }

    public record SubscriptionView(UUID id, UUID projectId, UUID environmentId,
            String endpointId, String eventType, int eventVersion, Map<String, String> filters,
            String status, String description, java.time.Instant createdAt) {
        static SubscriptionView from(EventSubscription subscription) {
            return new SubscriptionView(subscription.getId(), subscription.getProjectId(),
                    subscription.getEnvironmentId(), subscription.getEndpointId(),
                    subscription.getEventType(), subscription.getEventVersion(),
                    subscription.filterSet(), subscription.getStatus().name(),
                    subscription.getDescription(), subscription.getCreatedAt());
        }
    }

    public record DeliveryView(UUID id, UUID eventId, String eventType, UUID subscriptionId,
            UUID projectId, UUID environmentId, String endpointId, int attempt, String status,
            Integer responseCode, String errorCode, Integer latencyMs,
            java.time.Instant nextAttemptAt, java.time.Instant createdAt) {
        static DeliveryView from(EventDelivery delivery) {
            return new DeliveryView(delivery.getId(), delivery.getEventId(),
                    delivery.getEventType(), delivery.getSubscriptionId(),
                    delivery.getProjectId(), delivery.getEnvironmentId(),
                    delivery.getEndpointId(), delivery.getAttempt(),
                    delivery.getStatus().name(), delivery.getResponseCode(),
                    delivery.getErrorCode(), delivery.getLatencyMs(),
                    delivery.getNextAttemptAt(), delivery.getCreatedAt());
        }
    }
}
