package com.pesaguard.backend.organization.infrastructure;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.pesaguard.backend.organization.domain.OrganizationMembershipHistory;

public interface OrganizationMembershipHistoryRepository extends JpaRepository<OrganizationMembershipHistory, UUID> {

    List<OrganizationMembershipHistory> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId);

    List<OrganizationMembershipHistory> findByMembershipIdOrderByCreatedAtDesc(UUID membershipId);
}