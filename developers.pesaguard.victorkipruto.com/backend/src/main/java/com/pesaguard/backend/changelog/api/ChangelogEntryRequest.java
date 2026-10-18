package com.pesaguard.backend.changelog.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ChangelogEntryRequest(
        @NotBlank @Size(max = 64) String version,
        @NotBlank @Size(max = 160) String title,
        @NotBlank @Size(max = 10000) String body,
        @NotNull @Pattern(regexp = "FEATURE|IMPROVEMENT|FIX|SECURITY") String category) {
}
