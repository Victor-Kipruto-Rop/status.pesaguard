package com.pesaguard.backend.credentials.api;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ApiKeyCreationIdempotencyRepository
        extends JpaRepository<ApiKeyCreationIdempotency, UUID> {

    Optional<ApiKeyCreationIdempotency> findByOrganizationIdAndUserIdAndIdempotencyKeyHash(
            UUID organizationId, UUID userId, String idempotencyKeyHash);

    long deleteByExpiresAtBefore(Instant now);
}
