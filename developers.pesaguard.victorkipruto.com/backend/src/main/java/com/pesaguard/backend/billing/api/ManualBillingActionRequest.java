package com.pesaguard.backend.billing.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ManualBillingActionRequest(@NotBlank @Size(max = 500) String reason) {
}
