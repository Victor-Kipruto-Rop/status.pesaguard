package com.pesaguard.backend.billing.api;

import java.time.Instant;
import java.util.UUID;

public record BillingInvoiceRequestView(
        UUID id,
        String description,
        String status,
        Instant createdAt,
        Instant reviewedAt) {
}
