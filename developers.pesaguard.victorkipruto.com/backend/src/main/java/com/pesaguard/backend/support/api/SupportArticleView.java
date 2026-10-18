package com.pesaguard.backend.support.api;

import java.time.Instant;
import java.util.List;

import com.pesaguard.backend.support.domain.SupportArticle;

public record SupportArticleView(
        String id,
        String title,
        String slug,
        String summary,
        String content,
        String category,
        Instant updatedAt,
        List<String> tags,
        List<String> relatedArticleIds,
        List<String> relatedDocumentationUrls,
        List<String> relatedErrorCodes,
        List<String> relatedApiEndpoints) {

    public static SupportArticleView from(SupportArticle article) {
        return new SupportArticleView(article.getPublicId(), article.getTitle(), article.getSlug(),
                article.getSummary(), article.getContent(), article.getCategoryId(),
                article.getUpdatedAt(), split(article.getTags()), split(article.getRelatedArticleIds()),
                split(article.getRelatedDocumentationUrls()), split(article.getRelatedErrorCodes()),
                split(article.getRelatedApiEndpoints()));
    }

    private static List<String> split(String value) {
        return value == null || value.isBlank()
                ? List.of()
                : java.util.Arrays.stream(value.split("\\|"))
                        .map(String::trim)
                        .filter(part -> !part.isEmpty())
                        .toList();
    }
}
