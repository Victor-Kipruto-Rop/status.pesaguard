package com.pesaguard.backend.credentials.api;

import java.time.Clock;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ApiKeyCreationIdempotencyCleanup {

    private final ApiKeyCreationIdempotencyRepository repository;
    private final Clock clock;

    public ApiKeyCreationIdempotencyCleanup(ApiKeyCreationIdempotencyRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${pesaguard.credentials.idempotency-cleanup-ms:3600000}")
    @Transactional
    public void removeExpiredReplaySecrets() {
        repository.deleteByExpiresAtBefore(clock.instant());
    }
}
