package com.pesaguard.backend.sandbox.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Request for a fixed sandbox scenario; it deliberately has no URL or host field.
 */
public record RunSandboxScenarioRequest(
        @NotNull SandboxScenario scenario,
        @Min(1) @Max(100_000_000) Integer amount,
        @Pattern(regexp = "[A-Z]{3}") String currency) {

    public int effectiveAmount() {
        return amount == null ? 1250 : amount;
    }

    public String effectiveCurrency() {
        return currency == null ? "KES" : currency;
    }
}
