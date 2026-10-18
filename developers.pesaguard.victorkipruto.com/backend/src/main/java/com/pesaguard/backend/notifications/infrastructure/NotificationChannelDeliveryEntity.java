package com.pesaguard.backend.notifications.infrastructure;

import java.time.Instant;
import java.util.UUID;

import com.pesaguard.backend.notifications.domain.DeliveryState;
import com.pesaguard.backend.notifications.domain.Notification;
import com.pesaguard.backend.notifications.domain.NotificationChannel;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.IdClass;

/** Persisted state for one channel of one notification. */
@Entity
@Table(name = "notification_channel_deliveries")
@IdClass(NotificationChannelDeliveryId.class)
public class NotificationChannelDeliveryEntity {

    @Id
    @Column(name = "notification_id", nullable = false)
    private UUID notificationId;

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 16)
    private NotificationChannel channel;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "notification_id", insertable = false, updatable = false)
    private NotificationEntity notification;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 24)
    private DeliveryState state;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "last_error", length = 500)
    private String lastError;

    protected NotificationChannelDeliveryEntity() {}

    NotificationChannelDeliveryEntity(UUID notificationId, Notification.ChannelDelivery delivery,
            NotificationEntity notification) {
        this.notificationId = notificationId;
        this.notification = notification;
        update(delivery);
    }

    void update(Notification.ChannelDelivery delivery) {
        this.channel = delivery.channel();
        this.state = delivery.state();
        this.attempts = delivery.attempts();
        this.nextAttemptAt = delivery.nextAttemptAt();
        this.lastError = delivery.lastError();
    }

    public Notification.ChannelDelivery toDomain() {
        return new Notification.ChannelDelivery(channel, state, attempts, nextAttemptAt, lastError);
    }

    public NotificationChannel getChannel() { return channel; }
    public DeliveryState getState() { return state; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
}
