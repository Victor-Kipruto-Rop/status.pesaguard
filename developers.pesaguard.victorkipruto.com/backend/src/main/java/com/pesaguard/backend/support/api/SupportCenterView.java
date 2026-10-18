package com.pesaguard.backend.support.api;

import java.util.List;

public record SupportCenterView(
        String documentationUrl,
        String statusUrl,
        String communityUrl,
        List<SupportCategoryView> categories,
        List<SupportArticleView> latestArticles) {
}
