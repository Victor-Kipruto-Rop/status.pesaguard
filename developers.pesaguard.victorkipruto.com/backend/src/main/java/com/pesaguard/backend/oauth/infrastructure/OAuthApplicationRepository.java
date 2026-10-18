package com.pesaguard.backend.oauth.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.pesaguard.backend.oauth.domain.ApplicationStatus;
import com.pesaguard.backend.oauth.domain.OAuthApplication;

public interface OAuthApplicationRepository extends JpaRepository<OAuthApplication, UUID> {

    Optional<OAuthApplication> findByClientId(String clientId);

    Optional<OAuthApplication> findByIdAndOrganizationId(UUID id, UUID organizationId);

    List<OAuthApplication> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId);

    List<OAuthApplication> findByOrganizationIdAndStatusOrderByCreatedAtDesc(
            UUID organizationId, ApplicationStatus status);
}