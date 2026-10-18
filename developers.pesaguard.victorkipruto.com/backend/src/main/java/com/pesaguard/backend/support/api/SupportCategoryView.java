package com.pesaguard.backend.support.api;

import com.pesaguard.backend.support.domain.SupportCategory;

public record SupportCategoryView(String id, String name) {

    public static SupportCategoryView from(SupportCategory category) {
        return new SupportCategoryView(category.getId(), category.getName());
    }
}
