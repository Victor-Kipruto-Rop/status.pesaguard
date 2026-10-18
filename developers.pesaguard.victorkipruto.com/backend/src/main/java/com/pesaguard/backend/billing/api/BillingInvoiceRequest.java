package com.pesaguard.backend.billing.api;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record BillingInvoiceRequest(
        @NotNull UUID organizationId,
        UUID invoiceRequestId,
        @NotBlank @Size(max = 1000) String description,
        @Positive long amountMinor,
        @NotBlank @Pattern(regexp = "[A-Za-z]{3}") String currency,
        Instant dueAt,
        @NotBlank @Size(max = 500) String reason) {
}
