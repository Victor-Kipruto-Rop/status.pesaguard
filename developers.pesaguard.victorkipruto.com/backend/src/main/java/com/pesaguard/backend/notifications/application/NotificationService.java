package com.pesaguard.backend.notifications.application;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pesaguard.backend.notifications.domain.Notification;
import com.pesaguard.backend.notifications.domain.DeliveryState;
import com.pesaguard.backend.notifications.domain.NotificationCategory;
import com.pesaguard.backend.notifications.domain.NotificationChannel;
import com.pesaguard.backend.notifications.domain.NotificationPreferences;
import com.pesaguard.backend.notifications.domain.NotificationRetryPolicy;
import com.pesaguard.backend.notifications.domain.NotificationSeverity;
import com.pesaguard.backend.notifications.domain.NotificationType;
import com.pesaguard.backend.notifications.infrastructure.NotificationPreferenceEntity;
import com.pesaguard.backend.notifications.infrastructure.NotificationPreferenceRepository;
import com.pesaguard.backend.notifications.infrastructure.NotificationQueueEventEntity;
import com.pesaguard.backend.notifications.infrastructure.NotificationQueueEventRepository;
import com.pesaguard.backend.notifications.infrastructure.NotificationRepository;
import com.pesaguard.backend.member.infrastructure.UserAccountRepository;

/**
 * Enqueues notification events transactionally and delivers them asynchronously.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository notificationRepository;
    private final NotificationQueueEventRepository queueRepository;
    private final NotificationPreferenceRepository preferencesRepository;
    private final NotificationRuleEngine ruleEngine;
    private final Map<NotificationChannel, NotificationTransport> transports;
    private final NotificationRetryPolicy retryPolicy;
    private final Clock clock;
    private final UserAccountRepository userRepository;
    private final Random random = new Random();

    public NotificationService(NotificationRepository notificationRepository,
            NotificationQueueEventRepository queueRepository,
            NotificationPreferenceRepository preferencesRepository,
            NotificationRuleEngine ruleEngine, List<NotificationTransport> transports, Clock clock,
            UserAccountRepository userRepository) {
        this.notificationRepository = notificationRepository;
        this.queueRepository = queueRepository;
        this.preferencesRepository = preferencesRepository;
        this.ruleEngine = ruleEngine;
        EnumMap<NotificationChannel, NotificationTransport> byChannel =
                new EnumMap<>(NotificationChannel.class);
        for (NotificationTransport transport : transports) {
            byChannel.put(transport.channel(), transport);
        }
        this.transports = Map.copyOf(byChannel);
        this.retryPolicy = NotificationRetryPolicy.defaults();
        this.clock = clock;
        this.userRepository = userRepository;
    }

    /**
     * Enqueues a notification without waiting for an external delivery provider.
     *
     * @return the durable notification event id
     */
    @Transactional
    public UUID notify(UUID organizationId, UUID userId, NotificationType type,
            String subject, String body) {
        return enqueue(organizationId, userId, type, subject, body, null, null, null, null);
    }

    @Transactional
    public UUID enqueue(UUID organizationId, UUID userId, NotificationType type, String subject,
            String body, String resourceType, String resourceId, String actionUrl,
            String deduplicationKey) {
        UUID eventId = UUID.randomUUID();
        int inserted = queueRepository.insertIfAbsent(eventId, organizationId, userId,
                type.name(), ruleEngine.severityFor(type).name(), subject, body, resourceType,
                resourceId, actionUrl, deduplicationKey, clock.instant());
        if (inserted == 1) return eventId;
        if (deduplicationKey != null) {
            return queueRepository.findByOrganizationIdAndUserIdAndTypeAndDeduplicationKey(
                    organizationId, userId, type, deduplicationKey)
                    .map(NotificationQueueEventEntity::getId)
                    .orElseThrow(() -> new IllegalStateException(
                            "A duplicate notification event could not be retrieved."));
        }
        throw new IllegalStateException("A notification event could not be queued.");
    }

    /** Processes one queued event in a separate worker transaction. */
    @Transactional
    public boolean processQueuedEvent(UUID eventId) {
        NotificationQueueEventEntity event = queueRepository.findByIdForUpdate(eventId).orElse(null);
        if (event == null || event.getState() != NotificationQueueEventEntity.State.PENDING) {
            return false;
        }
        NotificationPreferences preferences = preferencesFor(event.getUserId());
        var channels = ruleEngine.channelsFor(event.getType(),
                preferences.channelsFor(event.getType()), availableChannels());
        Notification notification = Notification.addressedTo(event.getOrganizationId(),
                event.getUserId(), event.getType(), event.getSubject(), event.getBody(),
                preferences, channels.toArray(NotificationChannel[]::new));
        notification = notification.withAttempt(notification.id(), clock.instant(),
                notification.deliveries());
        String recipientAddress = userRepository.findById(event.getUserId())
                .map(com.pesaguard.backend.member.domain.UserAccount::getEmail)
                .orElse(null);
        Notification delivered = deliver(notification, recipientAddress);
        notificationRepository.saveAndFlush(new com.pesaguard.backend.notifications.infrastructure
                .NotificationEntity(delivered, event.getId(), event.getSeverity(),
                        event.getResourceType(), event.getResourceId(), event.getActionUrl()));
        event.markProcessed(clock.instant());
        queueRepository.save(event);
        return true;
    }

    @Transactional
    public void recordQueueFailure(UUID eventId, String error) {
        NotificationQueueEventEntity event = queueRepository.findByIdForUpdate(eventId).orElse(null);
        if (event == null || event.getState() != NotificationQueueEventEntity.State.PENDING) return;
        int nextAttempt = event.getAttempts() + 1;
        long delaySeconds = Math.min(300, 5L << Math.min(nextAttempt, 6));
        event.recordFailure(error, clock.instant().plusSeconds(delaySeconds), 8);
        queueRepository.save(event);
    }

    /**
     * Attempts every channel that is still outstanding.
     *
     * <p>Each channel is independent: a failed email does not undo a delivered
     * in-app record.
     */
    private Notification deliver(Notification notification, String recipientAddress) {
        Notification current = notification;
        for (Map.Entry<NotificationChannel, Notification.ChannelDelivery> entry
                : current.deliveries().entrySet()) {
            NotificationChannel channel = entry.getKey();
            Notification.ChannelDelivery delivery = entry.getValue();
            if (delivery.state() != DeliveryState.PENDING
                    && delivery.state() != DeliveryState.RETRY_SCHEDULED) {
                continue;
            }
            current = attempt(current, channel, recipientAddress);
        }
        return current;
    }

    /** Retries only channels whose persisted retry time has elapsed. */
    @Transactional
    public boolean retryDueChannels(UUID notificationId) {
        var stored = notificationRepository.findByIdForUpdate(notificationId).orElse(null);
        if (stored == null) return false;
        Notification notification = stored.toDomain();
        Instant now = clock.instant();
        boolean due = notification.deliveries().values().stream().anyMatch(delivery ->
                delivery.state() == DeliveryState.RETRY_SCHEDULED
                        && delivery.nextAttemptAt() != null
                        && !delivery.nextAttemptAt().isAfter(now));
        if (!due) return false;

        String recipientAddress = userRepository.findById(notification.userId())
                .map(com.pesaguard.backend.member.domain.UserAccount::getEmail)
                .orElse(null);
        Notification current = notification;
        for (Map.Entry<NotificationChannel, Notification.ChannelDelivery> entry
                : notification.deliveries().entrySet()) {
            var delivery = entry.getValue();
            if (delivery.state() == DeliveryState.RETRY_SCHEDULED
                    && delivery.nextAttemptAt() != null && !delivery.nextAttemptAt().isAfter(now)) {
                current = attempt(current, entry.getKey(), recipientAddress);
            }
        }
        stored.updateDeliveryState(current);
        notificationRepository.saveAndFlush(stored);
        return true;
    }

    private Notification attempt(Notification notification, NotificationChannel channel,
            String recipientAddress) {
        int attempt = notification.deliveries().get(channel).attempts() + 1;
        NotificationTransport transport = transports.get(channel);
        if (transport == null) {
            return notification.afterFailure(channel, "no transport registered", attempt,
                    java.time.Duration.ZERO, false, clock.instant());
        }

        try {
            NotificationTransport.DeliveryAttemptResult result = transport.send(notification,
                    recipientAddress);
            if (result.success()) {
                return notification.afterSuccess(channel, attempt, clock.instant(),
                        result.successState());
            }
            // A permanent failure is not retried; retrying an invalid address just
            // burns attempts that a real fault could have used.
            boolean willRetry = result.isTransient()
                    && notification.type().isRetryable()
                    && retryPolicy.shouldRetry(attempt);
            var delay = willRetry
                    ? retryPolicy.delayFor(attempt, random.nextDouble())
                    : java.time.Duration.ZERO;
            if (!willRetry) {
                log.error("notification permanently failed type={} channel={} notificationId={}",
                        notification.type(), channel, notification.id());
            }
            return notification.afterFailure(channel, result.error(), attempt, delay, willRetry,
                    clock.instant());
        } catch (RuntimeException failure) {
            // A throwing transport must not take down the request that raised the
            // notification. The user's API call is unrelated to our mail problems.
            log.error("notification transport threw type={} channel={}", notification.type(),
                    channel, failure);
            return notification.afterFailure(channel, String.valueOf(failure.getMessage()),
                    attempt, java.time.Duration.ZERO, false, clock.instant());
        }
    }

    /**
     * The user's stored preferences, or the safe defaults.
     *
     * <p>Preferences without a stored row use configured delivery-channel defaults.
     */
    @Transactional(readOnly = true)
    public NotificationPreferences preferencesFor(UUID userId) {
        var rows = preferencesRepository.findByUserId(userId);
        if (rows.isEmpty()) {
            // No stored preference means configured channels, not unavailable providers.
            return NotificationPreferences.defaultsFor(userId, availableChannels());
        }
        EnumMap<NotificationCategory, java.util.Set<NotificationChannel>> enabled =
                new EnumMap<>(NotificationCategory.class);
        for (var row : rows) {
            enabled.put(row.getCategory(), row.decode());
        }
        return NotificationPreferences.ofEnabled(userId, enabled, availableChannels());
    }

    /**
     * Stores preferences, rejecting any that would silence a mandatory event.
     */
    @Transactional
    public NotificationPreferences updatePreferences(UUID userId,
            Map<NotificationCategory, java.util.Set<NotificationChannel>> disabled) {
        NotificationPreferences validated = NotificationPreferences.of(userId, disabled,
                availableChannels());
        for (var entry : validated.enabledByCategory().entrySet()) {
            preferencesRepository.save(new NotificationPreferenceEntity(userId,
                    entry.getKey(), entry.getValue()));
        }
        return validated;
    }

    public NotificationRetryPolicy retryPolicy() {
        return retryPolicy;
    }

    public java.util.Set<NotificationChannel> availableChannels() {
        return java.util.Set.copyOf(transports.keySet());
    }
}
