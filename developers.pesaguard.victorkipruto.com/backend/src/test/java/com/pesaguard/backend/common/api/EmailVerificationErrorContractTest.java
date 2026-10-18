package com.pesaguard.backend.common.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import com.pesaguard.backend.common.exception.EmailVerificationRequiredException;

class EmailVerificationErrorContractTest {

    @Test
    void loginChallengeExposesCodeExpiryAndResendTimes() {
        Instant expiresAt = Instant.parse("2026-08-01T00:10:00Z");
        Instant resendAvailableAt = Instant.parse("2026-08-01T00:01:00Z");
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        ApiError error = handler.handleBusiness(
                new EmailVerificationRequiredException(expiresAt, resendAvailableAt, "person@example.com"),
                new MockHttpServletRequest()).getBody().error();

        assertThat(error.code()).isEqualTo("EMAIL_NOT_VERIFIED");
        assertThat(error.verificationExpiresAt()).isEqualTo(expiresAt);
        assertThat(error.verificationResendAvailableAt()).isEqualTo(resendAvailableAt);
        assertThat(error.verificationEmail()).isEqualTo("person@example.com");
        assertThat(error.selectableOrganizations()).isEmpty();
    }
}
