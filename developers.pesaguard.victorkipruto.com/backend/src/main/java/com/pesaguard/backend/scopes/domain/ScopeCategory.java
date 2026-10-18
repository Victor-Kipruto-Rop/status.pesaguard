package com.pesaguard.backend.scopes.domain;

import java.util.Set;

/**
 * The categories a scope can belong to.
 *
 * <p>Category is a presentation and review grouping, not a permission boundary.
 * Holding {@code payments:read} grants nothing in the payments category beyond
 * that one scope; categories never imply a bundle.
 */
public enum ScopeCategory {
    TRANSACTIONS("transactions"),
    PAYMENTS("payments"),
    RECONCILIATION("reconciliation"),
    FRAUD("fraud"),
    WEBHOOKS("webhooks"),
    DEVELOPER("developer");

    private final String value;

    ScopeCategory(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static Set<String> allValues() {
        return java.util.Arrays.stream(values()).map(ScopeCategory::value)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}