package com.pesaguard.backend.developerpreferences.domain;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "developer_preferences")
public class DeveloperPreferences {

    @Id
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "request_timeout_ms", nullable = false)
    private int requestTimeoutMs;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "theme", nullable = false, length = 16)
    private String theme;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected DeveloperPreferences() {
    }

    private DeveloperPreferences(
            UUID userId, int requestTimeoutMs, int retryCount, String theme, Instant updatedAt) {
        this.userId = userId;
        this.requestTimeoutMs = requestTimeoutMs;
        this.retryCount = retryCount;
        this.theme = theme;
        this.updatedAt = updatedAt;
    }

    public static DeveloperPreferences defaults(UUID userId, Instant now) {
        return new DeveloperPreferences(userId, 30000, 0, "SYSTEM", now);
    }

    public void update(int requestTimeoutMs, int retryCount, String theme, Instant now) {
        if (requestTimeoutMs < 1000 || requestTimeoutMs > 60000) {
            throw new IllegalArgumentException("Request timeout must be between 1000 and 60000 milliseconds.");
        }
        if (retryCount < 0 || retryCount > 5) {
            throw new IllegalArgumentException("Retry count must be between 0 and 5.");
        }
        if (theme != null && !Set.of("SYSTEM", "LIGHT", "DARK").contains(theme)) {
            throw new IllegalArgumentException("Theme must be SYSTEM, LIGHT, or DARK.");
        }
        this.requestTimeoutMs = requestTimeoutMs;
        this.retryCount = retryCount;
        if (theme != null) {
            this.theme = theme;
        }
        this.updatedAt = now;
    }

    public void updateTheme(String theme, Instant now) {
        if (!Set.of("SYSTEM", "LIGHT", "DARK").contains(theme)) {
            throw new IllegalArgumentException("Theme must be SYSTEM, LIGHT, or DARK.");
        }
        this.theme = theme;
        this.updatedAt = now;
    }

    public UUID getUserId() { return userId; }
    public int getRequestTimeoutMs() { return requestTimeoutMs; }
    public int getRetryCount() { return retryCount; }
    public String getTheme() { return theme; }
    public long getVersion() { return version; }
    public Instant getUpdatedAt() { return updatedAt; }
}
