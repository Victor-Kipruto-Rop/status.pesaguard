package com.pesaguard.backend.notifications.infrastructure;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.OneToMany;
import jakarta.persistence.CascadeType;
import com.pesaguard.backend.notifications.domain.NotificationSeverity;
import com.pesaguard.backend.notifications.domain.NotificationCategory;

/**
 * A stored notification.
 *
 * <p>Per-channel state is held in the {@code deliveries} column as a compact
 * string. A child table would be the more conventional shape, but the state is
 * always read and written with the notification as a whole and is bounded to two
 * configured channels, so keeping a compact summary alongside the delivery rows
 * avoids an additional read for inbox responses.
 */
@Entity
@Table(name = "notifications")
public class NotificationEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 48)
    private com.pesaguard.backend.notifications.domain.NotificationType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 24)
    private NotificationCategory category;

    @Column(name = "subject", nullable = false, length = 200)
    private String subject;

    @Column(name = "body", nullable = false, length = 4000)
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 16)
    private NotificationSeverity severity;

    @Column(name = "resource_type", length = 80)
    private String resourceType;

    @Column(name = "resource_id", length = 160)
    private String resourceId;

    @Column(name = "action_url", length = 1000)
    private String actionUrl;

    @Column(name = "event_id", nullable = false, unique = true)
    private UUID eventId;

    /**
     * Per-channel state, e.g. {@code EMAIL=RETRY_SCHEDULED:2;IN_APP=DELIVERED:1}.
     */
    @Column(name = "deliveries", nullable = false, length = 500)
    private String deliveries;

    @OneToMany(mappedBy = "notification", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<NotificationChannelDeliveryEntity> channelDeliveries = new ArrayList<>();

    @Column(name = "read_at")
    private Instant readAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected NotificationEntity() {
    }

    public NotificationEntity(com.pesaguard.backend.notifications.domain.Notification record,
            UUID eventId, NotificationSeverity severity, String resourceType,
            String resourceId, String actionUrl) {
        this.id = record.id();
        this.organizationId = record.organizationId();
        this.userId = record.userId();
        this.type = record.type();
        this.category = record.type().category();
        this.subject = record.subject();
        this.body = record.body();
        this.eventId = eventId;
        this.severity = severity;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.actionUrl = actionUrl;
        updateDeliveryState(record);
    }

    public void updateDeliveryState(com.pesaguard.backend.notifications.domain.Notification record) {
        this.deliveries = encode(record);
        Map<com.pesaguard.backend.notifications.domain.NotificationChannel,
                NotificationChannelDeliveryEntity> existing = new java.util.EnumMap<>(
                        com.pesaguard.backend.notifications.domain.NotificationChannel.class);
        channelDeliveries.forEach(delivery -> existing.put(delivery.getChannel(), delivery));
        record.deliveries().forEach((channel, delivery) -> {
            NotificationChannelDeliveryEntity stored = existing.remove(channel);
            if (stored == null) {
                channelDeliveries.add(new NotificationChannelDeliveryEntity(record.id(), delivery, this));
            } else {
                stored.update(delivery);
            }
        });
        if (!existing.isEmpty()) channelDeliveries.removeAll(existing.values());
    }

    public com.pesaguard.backend.notifications.domain.Notification toDomain() {
        Map<com.pesaguard.backend.notifications.domain.NotificationChannel,
                com.pesaguard.backend.notifications.domain.Notification.ChannelDelivery> states =
                        new EnumMap<>(com.pesaguard.backend.notifications.domain.NotificationChannel.class);
        channelDeliveries.forEach(delivery -> states.put(delivery.getChannel(), delivery.toDomain()));
        return new com.pesaguard.backend.notifications.domain.Notification(id, organizationId, userId,
                type, subject, body, createdAt, states);
    }

    private static String encode(
            com.pesaguard.backend.notifications.domain.Notification record) {
        StringBuilder encoded = new StringBuilder();
        for (var entry : record.deliveries().entrySet()) {
            if (encoded.length() > 0) {
                encoded.append(';');
            }
            var delivery = entry.getValue();
            encoded.append(entry.getKey().name())
                    .append('=')
                    .append(delivery.state().name())
                    .append(':')
                    .append(delivery.attempts());
            if (delivery.lastError() != null) {
                // Errors are stored so a failed delivery is explainable after the
                // fact. Separated by a space-free token to keep parsing trivial.
                encoded.append(':').append(sanitise(delivery.lastError()));
            }
        }
        return encoded.toString();
    }

    private static String sanitise(String error) {
        // Semicolons and separators would corrupt the encoding.
        return error.replace(';', ' ').replace('=', ' ');
    }

    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public UUID getUserId() { return userId; }
    public com.pesaguard.backend.notifications.domain.NotificationType getType() { return type; }
    public NotificationCategory getCategory() { return category; }
    public String getSubject() { return subject; }
    public String getBody() { return body; }
    public NotificationSeverity getSeverity() { return severity; }
    public String getResourceType() { return resourceType; }
    public String getResourceId() { return resourceId; }
    public String getActionUrl() { return actionUrl; }
    public UUID getEventId() { return eventId; }
    public String getDeliveries() { return deliveries; }
    public Instant getReadAt() { return readAt; }
    public Instant getCreatedAt() { return createdAt; }

    public void markRead(Instant at) {
        if (readAt == null) {
            readAt = at;
        }
    }
}
