package com.pesaguard.backend.support.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "support_article_feedback")
public class SupportArticleFeedback {

    @Id
    private UUID id;

    @Column(name = "article_id", nullable = false, updatable = false)
    private UUID articleId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "helpful", nullable = false)
    private boolean helpful;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SupportArticleFeedback() {
    }

    public SupportArticleFeedback(UUID articleId, UUID userId, boolean helpful, Instant now) {
        this.id = UUID.randomUUID();
        this.articleId = articleId;
        this.userId = userId;
        this.helpful = helpful;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void updateHelpful(boolean helpful, Instant now) {
        this.helpful = helpful;
        this.updatedAt = now;
    }
}
