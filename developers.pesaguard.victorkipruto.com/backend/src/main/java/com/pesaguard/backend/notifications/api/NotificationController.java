package com.pesaguard.backend.notifications.api;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Page;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import com.pesaguard.backend.common.api.ApiResponse;
import com.pesaguard.backend.notifications.application.NotificationService;
import com.pesaguard.backend.notifications.domain.NotificationCategory;
import com.pesaguard.backend.notifications.domain.NotificationChannel;
import com.pesaguard.backend.notifications.domain.NotificationSeverity;
import com.pesaguard.backend.notifications.infrastructure.NotificationEntity;
import com.pesaguard.backend.notifications.infrastructure.NotificationRepository;
import com.pesaguard.backend.security.principals.AuthenticatedUser;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationRepository notificationRepository;
    private final NotificationService notificationService;
    private final Clock clock;

    public NotificationController(NotificationRepository notificationRepository,
            NotificationService notificationService, Clock clock) {
        this.notificationRepository = notificationRepository;
        this.notificationService = notificationService;
        this.clock = clock;
    }

    @GetMapping
    ApiResponse<InboxPage> list(@AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) NotificationCategory category,
            @RequestParam(required = false) NotificationSeverity severity,
            @RequestParam(defaultValue = "false") boolean unreadOnly) {
        if (page < 0 || size < 1 || size > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Page must be non-negative and size must be between 1 and 100.");
        }
        Page<NotificationItem> items = notificationRepository
                .findInbox(principal.organizationId(), principal.userId(), category, severity,
                        unreadOnly, PageRequest.of(page, size))
                .map(NotificationItem::from);
        long unreadCount = notificationRepository.countByOrganizationIdAndUserIdAndReadAtIsNull(
                principal.organizationId(), principal.userId());
        return ApiResponse.of(new InboxPage(items.getContent(), unreadCount,
                items.getTotalElements(), items.getTotalPages(), items.getNumber(), items.getSize()));
    }

    @PostMapping("/{notificationId}/read")
    @Transactional
    ApiResponse<NotificationItem> markRead(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID notificationId) {
        NotificationEntity notification = notificationRepository
                .findByIdAndOrganizationIdAndUserId(notificationId, principal.organizationId(),
                        principal.userId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Notification was not found."));
        notification.markRead(clock.instant());
        NotificationItem markedRead = NotificationItem.from(notification);
        notificationRepository.delete(notification);
        return ApiResponse.of(markedRead);
    }

    @PostMapping("/read-all")
    @Transactional
    ApiResponse<MarkAllReadView> markAllRead(@AuthenticationPrincipal AuthenticatedUser principal) {
        Instant now = clock.instant();
        int updated = notificationRepository.markAllRead(
                principal.organizationId(), principal.userId(), now);
        int deleted = notificationRepository.deleteReadByOrganizationIdAndUserId(
                principal.organizationId(), principal.userId());
        return ApiResponse.of(new MarkAllReadView(updated, now, deleted));
    }

    @GetMapping("/preferences")
    ApiResponse<PreferenceView> preferences(@AuthenticationPrincipal AuthenticatedUser principal) {
        return ApiResponse.of(PreferenceView.from(notificationService
                .preferencesFor(principal.userId()), notificationService.availableChannels()));
    }

    @PutMapping("/preferences")
    ApiResponse<PreferenceView> updatePreferences(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody UpdatePreferencesRequest request) {
        var preferences = notificationService.updatePreferences(
                principal.userId(), request.disabledChannels());
        return ApiResponse.of(PreferenceView.from(preferences, notificationService.availableChannels()));
    }

    public record InboxView(List<NotificationItem> items, long unreadCount) {
        public InboxView {
            items = List.copyOf(items);
        }
    }

    public record InboxPage(List<NotificationItem> items, long unreadCount, long totalItems,
            int totalPages, int page, int size) {
        public InboxPage {
            items = List.copyOf(items);
        }
    }

    public record MarkAllReadView(int updated, Instant readAt, int deleted) {
    }

    public record NotificationItem(
            UUID id,
            String type,
            String category,
            String subject,
            String body,
            String severity,
            String resourceType,
            String resourceId,
            String actionUrl,
            Instant createdAt,
            Instant readAt,
            String deliveryState) {
        static NotificationItem from(NotificationEntity entity) {
            return new NotificationItem(entity.getId(), entity.getType().name(),
                    entity.getCategory().name(), entity.getSubject(), entity.getBody(),
                    entity.getSeverity().name(), entity.getResourceType(), entity.getResourceId(),
                    entity.getActionUrl(),
                    entity.getCreatedAt(), entity.getReadAt(), entity.getDeliveries());
        }
    }

    public record PreferenceView(
            Map<NotificationCategory, Set<NotificationChannel>> enabledChannels,
            Set<NotificationCategory> emailRequiredCategories,
            Set<NotificationChannel> availableChannels,
            Set<NotificationChannel> unavailableChannels) {

        public PreferenceView {
            enabledChannels = Map.copyOf(enabledChannels);
            emailRequiredCategories = Set.copyOf(emailRequiredCategories);
            availableChannels = Set.copyOf(availableChannels);
            unavailableChannels = Set.copyOf(unavailableChannels);
        }

        static PreferenceView from(
                com.pesaguard.backend.notifications.domain.NotificationPreferences preferences,
                Set<NotificationChannel> availableChannels) {
            Map<NotificationCategory, Set<NotificationChannel>> enabled =
                    new java.util.EnumMap<>(NotificationCategory.class);
            preferences.enabledByCategory().forEach((category, channels) -> enabled.put(category,
                    channels.stream().filter(availableChannels::contains)
                            .collect(java.util.stream.Collectors.toUnmodifiableSet())));
            return new PreferenceView(enabled,
                    java.util.Arrays.stream(NotificationCategory.values())
                            .filter(NotificationCategory::hasMandatoryEvents)
                            .collect(java.util.stream.Collectors.toUnmodifiableSet()),
                    availableChannels, java.util.Arrays.stream(NotificationChannel.values())
                            .filter(channel -> !availableChannels.contains(channel))
                            .collect(java.util.stream.Collectors.toUnmodifiableSet()));
        }
    }

    public record UpdatePreferencesRequest(
            @NotNull Map<NotificationCategory, Set<NotificationChannel>> disabledChannels) {
    }
}
