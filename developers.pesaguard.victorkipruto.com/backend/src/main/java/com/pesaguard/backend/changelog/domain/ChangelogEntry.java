package com.pesaguard.backend.changelog.domain;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "platform_changelog_entries")
public class ChangelogEntry {

    @Id
    private UUID id;

    @Column(nullable = false, length = 64)
    private String version;

    @Column(nullable = false, length = 160)
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    private String body;

    @Column(nullable = false, length = 24)
    private String category;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ChangelogEntry() {
    }

    private ChangelogEntry(String version, String title, String body, String category, UUID createdBy) {
        this.id = UUID.randomUUID();
        this.version = version.trim();
        this.title = title.trim();
        this.body = body.trim();
        this.category = category;
        this.status = "DRAFT";
        this.createdBy = createdBy;
    }

    public static ChangelogEntry draft(
            String version, String title, String body, String category, UUID createdBy) {
        return new ChangelogEntry(version, title, body, category, createdBy);
    }

    public void update(String version, String title, String body, String category) {
        if ("PUBLISHED".equals(status)) {
            throw new IllegalStateException("Published changelog entries cannot be edited.");
        }
        this.version = version.trim();
        this.title = title.trim();
        this.body = body.trim();
        this.category = category;
    }

    public void publish(Instant now) {
        if ("PUBLISHED".equals(status)) {
            return;
        }
        this.status = "PUBLISHED";
        this.publishedAt = now;
    }

    public UUID getId() { return id; }
    public String getVersion() { return version; }
    public String getTitle() { return title; }
    public String getBody() { return body; }
    public String getCategory() { return category; }
    public String getStatus() { return status; }
    public Instant getPublishedAt() { return publishedAt; }
    public UUID getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
