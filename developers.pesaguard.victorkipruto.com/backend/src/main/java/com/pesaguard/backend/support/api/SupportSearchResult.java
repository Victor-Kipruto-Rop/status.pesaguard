package com.pesaguard.backend.support.api;

public record SupportSearchResult(
        String type,
        String id,
        String title,
        String summary,
        String category,
        String href) {
}
