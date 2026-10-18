package com.pesaguard.backend.billing.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.pesaguard.backend.billing.domain.BillingPayment;

public interface BillingPaymentRepository extends JpaRepository<BillingPayment, UUID> {

    List<BillingPayment> findByInvoiceIdOrderByCreatedAtDesc(UUID invoiceId);

    List<BillingPayment> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId);

    Optional<BillingPayment> findByIdAndOrganizationId(UUID id, UUID organizationId);

    Optional<BillingPayment> findByProviderAndProviderReference(String provider, String providerReference);

    Optional<BillingPayment> findFirstByInvoiceIdAndProviderOrderByCreatedAtDesc(UUID invoiceId, String provider);

    Optional<BillingPayment> findByOrganizationIdAndIdempotencyKey(UUID organizationId, String idempotencyKey);
}
