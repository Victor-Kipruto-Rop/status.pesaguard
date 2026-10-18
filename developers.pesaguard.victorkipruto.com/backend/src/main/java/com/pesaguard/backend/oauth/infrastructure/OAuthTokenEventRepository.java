package com.pesaguard.backend.oauth.infrastructure;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.pesaguard.backend.oauth.domain.OAuthTokenEvent;

public interface OAuthTokenEventRepository extends JpaRepository<OAuthTokenEvent, UUID> {

    List<OAuthTokenEvent> findByApplicationIdOrderByCreatedAtDesc(UUID applicationId);
}