package com.pesaguard.backend.credentials.api;

import java.time.Instant;
import java.util.List;

public record SandboxTransactionsView(
        List<SandboxTransaction> data,
        boolean hasMore,
        boolean sandbox,
        Instant generatedAt) {

    public SandboxTransactionsView {
        data = List.copyOf(data);
    }

    public record SandboxTransaction(
            String id,
            long amount,
            String currency,
            String status,
            boolean test) {
    }
}
