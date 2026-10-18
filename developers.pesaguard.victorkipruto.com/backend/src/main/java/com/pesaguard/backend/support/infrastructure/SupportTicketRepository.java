package com.pesaguard.backend.support.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;

import com.pesaguard.backend.support.domain.SupportTicket;
import jakarta.persistence.LockModeType;

public interface SupportTicketRepository extends JpaRepository<SupportTicket, UUID> {

    Page<SupportTicket> findByOrganizationIdAndUserIdOrderByUpdatedAtDesc(
            UUID organizationId, UUID userId, Pageable pageable);

    Optional<SupportTicket> findByPublicIdAndOrganizationIdAndUserId(
            String publicId, UUID organizationId, UUID userId);

    org.springframework.data.domain.Page<SupportTicket> findAllByOrderByUpdatedAtDesc(Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select ticket from SupportTicket ticket where ticket.publicId = :publicId")
    Optional<SupportTicket> findByPublicIdForUpdate(@Param("publicId") String publicId);

    @Query("""
            select ticket from SupportTicket ticket
            where ticket.organizationId = :organizationId
              and ticket.userId = :userId
              and (lower(ticket.subject) like lower(concat('%', :query, '%'))
                   or lower(ticket.description) like lower(concat('%', :query, '%')))
            order by ticket.updatedAt desc
            """)
    List<SupportTicket> searchOwnedTickets(
            @Param("organizationId") UUID organizationId,
            @Param("userId") UUID userId,
            @Param("query") String query,
            Pageable pageable);
}
