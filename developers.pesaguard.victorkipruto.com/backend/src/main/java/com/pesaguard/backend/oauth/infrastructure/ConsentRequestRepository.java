package com.pesaguard.backend.oauth.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.pesaguard.backend.oauth.domain.ConsentRequest;
import com.pesaguard.backend.oauth.domain.ConsentStatus;

public interface ConsentRequestRepository extends JpaRepository<ConsentRequest, UUID> {

    Optional<ConsentRequest> findByIdAndUserId(UUID id, UUID userId);

    List<ConsentRequest> findByUserIdOrderByCreatedAtDesc(UUID userId);

    List<ConsentRequest> findByUserIdAndStatusOrderByCreatedAtDesc(UUID userId, ConsentStatus status);
}