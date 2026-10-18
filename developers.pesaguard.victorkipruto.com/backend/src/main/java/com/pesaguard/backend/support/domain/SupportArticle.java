package com.pesaguard.backend.support.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "support_articles")
public class SupportArticle {

    @Id
    private UUID id;

    @Column(name = "public_id", nullable = false, unique = true, length = 80)
    private String publicId;

    @Column(name = "title", nullable = false, length = 180)
    private String title;

    @Column(name = "slug", nullable = false, unique = true, length = 180)
    private String slug;

    @Column(name = "summary", nullable = false, length = 500)
    private String summary;

    @Column(name = "content", nullable = false, columnDefinition = "text")
    private String content;

    @Column(name = "category_id", nullable = false, length = 40)
    private String categoryId;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "tags", nullable = false, columnDefinition = "text")
    private String tags;

    @Column(name = "keywords", nullable = false, columnDefinition = "text")
    private String keywords;

    @Column(name = "related_article_ids", nullable = false, columnDefinition = "text")
    private String relatedArticleIds;

    @Column(name = "related_documentation_urls", nullable = false, columnDefinition = "text")
    private String relatedDocumentationUrls;

    @Column(name = "related_error_codes", nullable = false, columnDefinition = "text")
    private String relatedErrorCodes;

    @Column(name = "related_api_endpoints", nullable = false, columnDefinition = "text")
    private String relatedApiEndpoints;

    protected SupportArticle() {
    }

    public UUID getId() { return id; }
    public String getPublicId() { return publicId; }
    public String getTitle() { return title; }
    public String getSlug() { return slug; }
    public String getSummary() { return summary; }
    public String getContent() { return content; }
    public String getCategoryId() { return categoryId; }
    public String getStatus() { return status; }
    public Instant getPublishedAt() { return publishedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public String getTags() { return tags; }
    public String getKeywords() { return keywords; }
    public String getRelatedArticleIds() { return relatedArticleIds; }
    public String getRelatedDocumentationUrls() { return relatedDocumentationUrls; }
    public String getRelatedErrorCodes() { return relatedErrorCodes; }
    public String getRelatedApiEndpoints() { return relatedApiEndpoints; }
}
