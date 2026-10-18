package com.pesaguard.backend.developerpreferences.api;

public record DeveloperPreferencesView(
        int requestTimeoutMs, int retryCount, String theme, long version) {
}
