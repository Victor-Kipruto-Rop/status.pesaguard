package com.pesaguard.backend.serviceaccount.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.pesaguard.backend.serviceaccount.domain.ServiceAccountAccessToken;

public interface ServiceAccountAccessTokenRepository extends JpaRepository<ServiceAccountAccessToken, UUID> {

    Optional<ServiceAccountAccessToken> findByTokenHash(String tokenHash);

    List<ServiceAccountAccessToken> findByServiceAccountId(UUID serviceAccountId);
}
