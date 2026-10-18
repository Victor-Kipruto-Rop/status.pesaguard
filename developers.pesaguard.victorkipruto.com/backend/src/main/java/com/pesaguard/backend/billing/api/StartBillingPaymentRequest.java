package com.pesaguard.backend.billing.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record StartBillingPaymentRequest(
        @NotBlank @Pattern(regexp = "STRIPE|PAYHERO|DARAJA|AIRTEL_MONEY|MANUAL") String provider,
        @Size(max = 32) String phoneNumber) {
}
