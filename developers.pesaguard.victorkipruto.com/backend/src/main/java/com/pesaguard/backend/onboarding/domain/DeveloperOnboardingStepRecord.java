package com.pesaguard.backend.onboarding.domain;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

@Entity
@Table(name = "developer_onboarding_steps", uniqueConstraints = @UniqueConstraint(
        name = "uq_developer_onboarding_step_user_key", columnNames = {"user_id", "step_key"}))
public class DeveloperOnboardingStepRecord {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "step_key", nullable = false, length = 40)
    private String stepKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private DeveloperOnboardingStepStatus status;

    @Column(name = "required", nullable = false)
    private boolean required;

    @Column(name = "conditional", nullable = false)
    private boolean conditional;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "skipped_at")
    private Instant skippedAt;

    @Column(name = "blocked_reason", length = 500)
    private String blockedReason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> metadata = new LinkedHashMap<>();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected DeveloperOnboardingStepRecord() {
    }

    private DeveloperOnboardingStepRecord(
            UUID userId, String stepKey, boolean required, boolean conditional, Instant now) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.stepKey = stepKey;
        this.required = required;
        this.conditional = conditional;
        this.status = DeveloperOnboardingStepStatus.IN_PROGRESS;
        this.startedAt = now;
    }

    public static DeveloperOnboardingStepRecord start(
            UUID userId, String stepKey, boolean required, boolean conditional, Instant now) {
        return new DeveloperOnboardingStepRecord(userId, stepKey, required, conditional, now);
    }

    public void start(Instant now) {
        if (status == DeveloperOnboardingStepStatus.COMPLETED) {
            return;
        }
        status = DeveloperOnboardingStepStatus.IN_PROGRESS;
        startedAt = startedAt == null ? now : startedAt;
        skippedAt = null;
        blockedReason = null;
        updatedAt = now;
    }

    public void complete(Instant now) {
        status = DeveloperOnboardingStepStatus.COMPLETED;
        completedAt = now;
        skippedAt = null;
        blockedReason = null;
        updatedAt = now;
    }

    public void skip(Instant now) {
        status = DeveloperOnboardingStepStatus.SKIPPED;
        skippedAt = now;
        completedAt = null;
        blockedReason = null;
        updatedAt = now;
    }

    public void block(String reason, Instant now) {
        status = DeveloperOnboardingStepStatus.BLOCKED;
        blockedReason = reason;
        completedAt = null;
        updatedAt = now;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getStepKey() { return stepKey; }
    public DeveloperOnboardingStepStatus getStatus() { return status; }
    public boolean isRequired() { return required; }
    public boolean isConditional() { return conditional; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public Instant getSkippedAt() { return skippedAt; }
    public String getBlockedReason() { return blockedReason; }
    public Map<String, Object> getMetadata() { return Map.copyOf(metadata); }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
}
