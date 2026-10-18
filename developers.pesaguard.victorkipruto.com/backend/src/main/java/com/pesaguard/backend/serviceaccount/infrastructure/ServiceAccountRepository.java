package com.pesaguard.backend.serviceaccount.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.pesaguard.backend.serviceaccount.domain.ServiceAccount;

public interface ServiceAccountRepository extends JpaRepository<ServiceAccount, UUID> {

    List<ServiceAccount> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId);

    Optional<ServiceAccount> findByIdAndOrganizationId(UUID id, UUID organizationId);

    Optional<ServiceAccount> findByClientId(String clientId);
}
