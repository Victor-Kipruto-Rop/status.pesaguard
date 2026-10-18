package com.pesaguard.backend.billing.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record BillingInvoiceRequestCreate(@NotBlank @Size(max = 1000) String description) {
}
