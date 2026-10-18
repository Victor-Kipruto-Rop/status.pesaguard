package com.pesaguard.backend.security.authentication;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.Test;

import jakarta.validation.Validation;
import jakarta.validation.Validator;

class LoginRequestTest {

    @Test
    void acceptsPasswordLoginWithoutAnyAuthenticatorOrRecoveryCodeField() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();

            Set<?> violations = validator.validate(
                    new LoginRequest("developer@example.com", "correct-password", null));

            assertThat(violations).isEmpty();
        }
    }

    @Test
    void rejectsOversizedLoginCredentials() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();

            Set<?> violations = validator.validate(
                    new LoginRequest("developer@example.com", "x".repeat(129), null));

            assertThat(violations).isNotEmpty();
        }
    }
}
