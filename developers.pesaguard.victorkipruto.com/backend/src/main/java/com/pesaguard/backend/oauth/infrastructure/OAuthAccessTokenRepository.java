package com.pesaguard.backend.oauth.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.pesaguard.backend.oauth.domain.OAuthAccessToken;

public interface OAuthAccessTokenRepository extends JpaRepository<OAuthAccessToken, UUID> {

    Optional<OAuthAccessToken> findByTokenHash(String tokenHash);

    List<OAuthAccessToken> findByFamilyId(UUID familyId);

    List<OAuthAccessToken> findByApplicationId(UUID applicationId);
}