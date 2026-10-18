package com.pesaguard.backend.loadtest.api;

import jakarta.validation.constraints.Size;

public record CreateRunRequest(
        @Size(max = 64) String productionConfirmationToken) {
}
