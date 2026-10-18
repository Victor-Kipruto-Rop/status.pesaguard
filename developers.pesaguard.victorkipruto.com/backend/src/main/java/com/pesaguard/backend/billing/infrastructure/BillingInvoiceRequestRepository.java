package com.pesaguard.backend.billing.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.pesaguard.backend.billing.domain.BillingInvoiceRequest;

public interface BillingInvoiceRequestRepository extends JpaRepository<BillingInvoiceRequest, UUID> {

    List<BillingInvoiceRequest> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId);

    Optional<BillingInvoiceRequest> findByIdAndOrganizationId(UUID id, UUID organizationId);

    List<BillingInvoiceRequest> findByStatusOrderByCreatedAtAsc(String status);
}
