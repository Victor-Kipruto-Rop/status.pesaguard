package com.pesaguard.backend.security.authentication;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import com.fasterxml.jackson.annotation.JsonAlias;

public record RegisterRequest(
        @NotBlank @Email @Size(max = 320) String email,
        @NotBlank @Size(max = 128) String password,
        @JsonAlias("name") @NotBlank @Size(min = 2, max = 120) String displayName,
        @NotBlank @Size(min = 2, max = 120) String organizationName,
        @Size(max = 500) String organizationDescription,
        @AssertTrue(message = "Terms must be accepted") boolean termsAccepted,
        String username,
        @Pattern(regexp = "\\+[1-9][0-9]{1,14}") String phoneNumber) {
}
