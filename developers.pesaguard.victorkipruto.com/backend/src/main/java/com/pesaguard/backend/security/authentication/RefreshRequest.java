package com.pesaguard.backend.security.authentication;

import java.util.List;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Body for {@code POST /api/v1/auth/refresh}. */
public record RefreshRequest(
        @NotBlank @Size(max = 512) String refreshToken) {
}