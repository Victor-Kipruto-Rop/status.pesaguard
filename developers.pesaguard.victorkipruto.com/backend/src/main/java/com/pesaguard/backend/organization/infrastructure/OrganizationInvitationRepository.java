package com.pesaguard.backend.organization.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.pesaguard.backend.organization.domain.InvitationStatus;
import com.pesaguard.backend.organization.domain.OrganizationInvitation;
import jakarta.persistence.LockModeType;

public interface OrganizationInvitationRepository extends JpaRepository<OrganizationInvitation, UUID> {

    List<OrganizationInvitation> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId);

    Optional<OrganizationInvitation> findByIdAndOrganizationId(UUID id, UUID organizationId);

    Optional<OrganizationInvitation> findByOrganizationIdAndInvitedByAndIdempotencyKeyHash(
            UUID organizationId, UUID invitedBy, String idempotencyKeyHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from OrganizationInvitation i where i.id = :id")
    Optional<OrganizationInvitation> findByIdForUpdate(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from OrganizationInvitation i where i.id = :id and i.organizationId = :organizationId")
    Optional<OrganizationInvitation> findByIdAndOrganizationIdForUpdate(
            @Param("id") UUID id, @Param("organizationId") UUID organizationId);

    Optional<OrganizationInvitation> findByTokenHash(String tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from OrganizationInvitation i where i.tokenHash = :tokenHash")
    Optional<OrganizationInvitation> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    boolean existsByOrganizationIdAndEmailIgnoreCaseAndStatus(
            UUID organizationId, String email, InvitationStatus status);
}
