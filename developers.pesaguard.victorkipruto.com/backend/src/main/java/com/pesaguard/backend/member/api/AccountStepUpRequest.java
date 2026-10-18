package com.pesaguard.backend.member.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AccountStepUpRequest(
        @NotBlank @Size(max = 256) String currentPassword,
        @Size(max = 32) @Pattern(regexp = "[0-9A-Za-z_-]{6,32}") String mfaCode) {
}
