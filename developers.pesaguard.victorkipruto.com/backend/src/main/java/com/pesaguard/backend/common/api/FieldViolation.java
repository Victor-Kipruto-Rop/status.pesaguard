package com.pesaguard.backend.common.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record FieldViolation(@NotBlank String field, @NotBlank String message) {
}
