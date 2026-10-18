package com.pesaguard.backend.billing.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.pesaguard.backend.billing.domain.BillingInvoice;

public interface BillingInvoiceRepository extends JpaRepository<BillingInvoice, UUID> {

    List<BillingInvoice> findByOrganizationIdOrderByIssuedAtDesc(UUID organizationId);

    Optional<BillingInvoice> findByIdAndOrganizationId(UUID id, UUID organizationId);
}
