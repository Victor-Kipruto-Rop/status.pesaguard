package com.pesaguard.backend.common.api;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;

public record ApiResponse<T>(@NotNull T data, @NotNull UUID requestId) {

    public static <T> ApiResponse<T> of(T data) {
        return new ApiResponse<>(data, RequestContext.currentRequestId());
    }
}
