package com.pesaguard.backend.rbac.api;

import jakarta.validation.constraints.Size;

public record ProductionAccessReviewRequest(
        @Size(max = 1000) String note,
        @Size(max = 40) String expiresIn) {
}