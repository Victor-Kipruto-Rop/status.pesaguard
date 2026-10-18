package com.pesaguard.backend.billing.api;

import java.time.Instant;
import java.util.UUID;

public record BillingPaymentView(
        UUID id,
        UUID invoiceId,
        String provider,
        String status,
        String providerReference,
        String checkoutUrl,
        String phoneLastFour,
        Instant createdAt,
        Instant paidAt) {
}
