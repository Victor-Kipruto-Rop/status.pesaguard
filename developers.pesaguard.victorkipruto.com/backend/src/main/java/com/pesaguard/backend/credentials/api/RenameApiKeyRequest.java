package com.pesaguard.backend.credentials.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RenameApiKeyRequest(@NotBlank @Size(min = 2, max = 120) String name) {
}
