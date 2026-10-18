package com.pesaguard.backend.rbac.api;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateRoleRequest(
        @NotBlank @Size(min = 2, max = 64) String name,
        @Size(max = 500) String description,
        @NotEmpty @Size(max = 50) List<@Pattern(regexp = "[a-z_]+:[a-z_]+") String> permissions) {
}