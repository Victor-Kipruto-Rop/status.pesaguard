package com.pesaguard.backend.onboarding.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "developer_onboarding_progress")
public class DeveloperOnboardingProgress {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "current_step", nullable = false, length = 40)
    private String currentStep;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "completed_steps", nullable = false, columnDefinition = "jsonb")
    private List<String> completedSteps;

    @Column(name = "skipped", nullable = false)
    private boolean skipped;

    @Column(name = "completed", nullable = false)
    private boolean completed;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected DeveloperOnboardingProgress() {
    }

    private DeveloperOnboardingProgress(UUID userId, Instant now) {
        this.userId = userId;
        this.currentStep = "welcome";
        this.completedSteps = new ArrayList<>();
        this.skipped = false;
        this.completed = false;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static DeveloperOnboardingProgress start(UUID userId, Instant now) {
        return new DeveloperOnboardingProgress(userId, now);
    }

    public void update(String step, String completedStep, Instant now) {
        if (completed) {
            throw new IllegalStateException("Completed onboarding cannot be changed.");
        }
        currentStep = step;
        skipped = false;
        if (completedStep != null && !completedSteps.contains(completedStep)) {
            completedSteps.add(completedStep);
        }
        updatedAt = now;
    }

    public void skip(Instant now) {
        skipped = true;
        updatedAt = now;
    }

    public void resume(String step, Instant now) {
        if (!completed) {
            currentStep = step;
            skipped = false;
            updatedAt = now;
        }
    }

    public void complete(List<String> allSteps, Instant now) {
        completedSteps = new ArrayList<>(allSteps);
        currentStep = "complete";
        skipped = false;
        completed = true;
        updatedAt = now;
    }

    public String getCurrentStep() {
        return currentStep;
    }

    public List<String> getCompletedSteps() {
        return List.copyOf(completedSteps);
    }

    public boolean isSkipped() {
        return skipped;
    }

    public boolean isCompleted() {
        return completed;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }
}
