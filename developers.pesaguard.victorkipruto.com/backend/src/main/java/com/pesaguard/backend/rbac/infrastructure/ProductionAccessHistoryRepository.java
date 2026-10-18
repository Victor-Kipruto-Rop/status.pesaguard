package com.pesaguard.backend.rbac.infrastructure;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.pesaguard.backend.rbac.domain.ProductionAccessHistoryEntry;

public interface ProductionAccessHistoryRepository
        extends JpaRepository<ProductionAccessHistoryEntry, UUID> {

    /**
     * A request's full trail, oldest first.
     *
     * <p>Always scoped to the organization as well as the request. The request id
     * is taken from a URL, so relying on it alone would let one tenant read
     * another's review history by guessing a UUID.
     */
    List<ProductionAccessHistoryEntry> findByRequestIdAndOrganizationIdOrderByRecordedAtAsc(
            UUID requestId, UUID organizationId);
}