package com.pesaguard.backend.notifications.infrastructure;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.pesaguard.backend.notifications.domain.NotificationCategory;
import com.pesaguard.backend.notifications.domain.NotificationChannel;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/**
 * One row per user and category: which channels are enabled for it.
 *
 * <p>Stores what is <b>enabled</b>, not what is disabled. Categories introduced
 * after a user configured preferences use the configured email and in-app defaults.
 */
@Entity
@Table(name = "notification_preferences")
@IdClass(NotificationPreferenceId.class)
public class NotificationPreferenceEntity {

    @Id
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 24)
    private NotificationCategory category;

    /** Comma-separated, e.g. {@code IN_APP}. */
    @Column(name = "enabled_channels", nullable = false, length = 64)
    private String enabledChannels;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private java.time.Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private java.time.Instant updatedAt;

    protected NotificationPreferenceEntity() {
    }

    public NotificationPreferenceEntity(UUID userId, NotificationCategory category,
            Set<NotificationChannel> channels) {
        this.userId = userId;
        this.category = category;
        this.enabledChannels = encode(channels);
    }

    private static String encode(Set<NotificationChannel> channels) {
        StringBuilder encoded = new StringBuilder();
        for (NotificationChannel channel : NotificationChannel.values()) {
            if (channels.contains(channel)) {
                if (encoded.length() > 0) {
                    encoded.append(',');
                }
                encoded.append(channel.name());
            }
        }
        return encoded.toString();
    }

    /**
     * Decodes to a set, defaulting to configured channels when nothing is listed.
     */
    public EnumSet<NotificationChannel> decode() {
        if (enabledChannels == null || enabledChannels.isBlank()) {
            return EnumSet.of(NotificationChannel.EMAIL, NotificationChannel.IN_APP);
        }
        EnumSet<NotificationChannel> decoded = EnumSet.noneOf(NotificationChannel.class);
        for (String token : enabledChannels.split(",")) {
            try {
                decoded.add(NotificationChannel.valueOf(token.trim()));
            } catch (IllegalArgumentException unknown) {
                // An unrecognised token is ignored rather than failing the read;
                // the in-app default above keeps the record usable.
            }
        }
        if (decoded.isEmpty()) {
            return EnumSet.of(NotificationChannel.EMAIL, NotificationChannel.IN_APP);
        }
        decoded.add(NotificationChannel.IN_APP);
        return decoded;
    }

    public UUID getUserId() { return userId; }
    public NotificationCategory getCategory() { return category; }
    public String getEnabledChannels() { return enabledChannels; }
}