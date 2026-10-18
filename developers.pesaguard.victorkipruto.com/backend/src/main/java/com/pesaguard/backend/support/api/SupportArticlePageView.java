package com.pesaguard.backend.support.api;

import java.util.List;

public record SupportArticlePageView(
        List<SupportArticleView> articles,
        int page,
        int pageSize,
        long totalItems,
        int totalPages) {
}
