package com.pesaguard.backend.organization.infrastructure;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.pesaguard.backend.organization.domain.InvitationEmailDelivery;

import jakarta.persistence.LockModeType;

public interface InvitationEmailDeliveryRepository extends JpaRepository<InvitationEmailDelivery, UUID> {

    Optional<InvitationEmailDelivery> findFirstByInvitationIdOrderByCreatedAtDesc(UUID invitationId);

    @Query("select d.id from InvitationEmailDelivery d "
            + "where d.status = com.pesaguard.backend.organization.domain.InvitationEmailDeliveryStatus.PENDING "
            + "and d.nextAttemptAt <= :now order by d.createdAt asc")
    List<UUID> findDueIds(@Param("now") Instant now, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from InvitationEmailDelivery d where d.id = :id "
            + "and d.status = com.pesaguard.backend.organization.domain.InvitationEmailDeliveryStatus.PENDING "
            + "and d.nextAttemptAt <= :now")
    Optional<InvitationEmailDelivery> findDueByIdForUpdate(@Param("id") UUID id, @Param("now") Instant now);
}
