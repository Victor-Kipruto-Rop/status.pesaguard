package com.pesaguard.backend.common.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.UUID;

import com.pesaguard.backend.common.exception.MfaEnrollmentRequiredException;
import com.pesaguard.backend.common.exception.ResourceConflictException;

class GlobalExceptionHandlerTest {

    @Test
    void returnsActionableEmailAlreadyRegisteredMessage() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/register");

        ResponseEntity<ApiErrorResponse> response = new GlobalExceptionHandler().handleBusiness(
                new ResourceConflictException(
                        "EMAIL_ALREADY_REGISTERED",
                        "An account already exists for this email."),
                request);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("EMAIL_ALREADY_REGISTERED", response.getBody().error().code());
        assertEquals(
                "An account already exists for this email. Sign in or reset your password.",
                response.getBody().error().message());
    }

    @Test
    void exceptionDiagnosticsKeepStackFramesButOmitExceptionMessages() {
        IllegalStateException exception = new IllegalStateException("token=private-value");

        String diagnostics = SafeExceptionDiagnostics.stackTrace(exception);

        assertTrue(diagnostics.contains(IllegalStateException.class.getName()));
        assertTrue(diagnostics.contains("GlobalExceptionHandlerTest"));
        assertFalse(diagnostics.contains("private-value"));
    }

    @Test
    void preservesStatusButDoesNotReflectResponseStatusReason() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/key-data/usage");
        ResponseStatusException exception = new ResponseStatusException(
                HttpStatus.UNAUTHORIZED, "A valid API key is required.");

        ResponseEntity<ApiErrorResponse> response =
                new GlobalExceptionHandler().handleResponseStatus(exception, request);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("REQUEST_REJECTED", response.getBody().error().code());
        assertEquals("Please sign in to continue.", response.getBody().error().message());
    }

    @Test
    void unexpectedServerReasonIsReplacedBySafePublicMessage() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/usage");
        ResponseStatusException exception = new ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "SQLException: password=secret from database.internal:5432");

        ResponseEntity<ApiErrorResponse> response =
                new GlobalExceptionHandler().handleResponseStatus(exception, request);

        assertEquals("Something went wrong. Please try again.", response.getBody().error().message());
    }

    @Test
    void mfaEnrollmentChallengeIsNoStoreAndCarriesOnlyTheRestrictedToken() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        Instant expiresAt = Instant.now().plusSeconds(300);

        ResponseEntity<ApiErrorResponse> response = new GlobalExceptionHandler().handleBusiness(
                new MfaEnrollmentRequiredException("restricted-token", expiresAt), request);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertEquals("no-store", response.getHeaders().getFirst("Cache-Control"));
        assertNotNull(response.getBody());
        assertEquals("MFA_ENROLLMENT_REQUIRED", response.getBody().error().code());
        assertEquals("restricted-token", response.getBody().error().mfaEnrollmentToken());
        assertEquals(expiresAt, response.getBody().error().mfaEnrollmentExpiresAt());
    }
}
