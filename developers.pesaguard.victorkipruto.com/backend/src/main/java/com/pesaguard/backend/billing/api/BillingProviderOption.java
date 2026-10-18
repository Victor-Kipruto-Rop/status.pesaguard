package com.pesaguard.backend.billing.api;

public record BillingProviderOption(String id, String label, boolean available, boolean requiresPhone) {
}
