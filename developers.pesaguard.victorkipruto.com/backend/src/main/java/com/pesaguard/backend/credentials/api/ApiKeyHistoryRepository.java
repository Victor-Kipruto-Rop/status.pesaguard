package com.pesaguard.backend.credentials.api;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ApiKeyHistoryRepository extends JpaRepository<ApiKeyHistory, UUID> {

    List<ApiKeyHistory> findByApiKeyIdOrderByCreatedAtDesc(UUID apiKeyId);
}