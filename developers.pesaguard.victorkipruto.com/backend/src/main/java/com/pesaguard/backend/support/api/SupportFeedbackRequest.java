package com.pesaguard.backend.support.api;

import jakarta.validation.constraints.NotNull;

public record SupportFeedbackRequest(@NotNull Boolean helpful) {
}
