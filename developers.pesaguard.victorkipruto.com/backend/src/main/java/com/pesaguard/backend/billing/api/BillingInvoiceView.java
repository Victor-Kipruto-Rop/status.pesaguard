package com.pesaguard.backend.billing.api;

import java.time.Instant;
import java.util.UUID;

public record BillingInvoiceView(
        UUID id,
        String invoiceNumber,
        String description,
        long amountMinor,
        String currency,
        String status,
        Instant dueAt,
        Instant issuedAt,
        Instant paidAt) {
}
