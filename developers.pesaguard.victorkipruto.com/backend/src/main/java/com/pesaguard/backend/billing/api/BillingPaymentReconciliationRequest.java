package com.pesaguard.backend.billing.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record BillingPaymentReconciliationRequest(
        @NotBlank @Pattern(regexp = "PAID|FAILED") String status,
        @NotBlank @Size(max = 500) String reason) {
}
