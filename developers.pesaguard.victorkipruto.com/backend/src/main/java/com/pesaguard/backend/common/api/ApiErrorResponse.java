package com.pesaguard.backend.common.api;

import jakarta.validation.constraints.NotNull;

public record ApiErrorResponse(@NotNull ApiError error) {
}
