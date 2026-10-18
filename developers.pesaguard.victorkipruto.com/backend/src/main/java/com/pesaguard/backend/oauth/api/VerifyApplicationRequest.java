package com.pesaguard.backend.oauth.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record VerifyApplicationRequest(
        @NotBlank @Size(min = 2, max = 120) String reference) {
}