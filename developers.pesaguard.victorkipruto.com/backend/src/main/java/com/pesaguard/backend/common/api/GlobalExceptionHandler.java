package com.pesaguard.backend.common.api;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.pesaguard.backend.common.exception.BusinessException;
import com.pesaguard.backend.common.exception.EmailVerificationRequiredException;
import com.pesaguard.backend.security.authentication.LoginEmailMfaRequiredException;
import com.pesaguard.backend.common.exception.MfaEnrollmentRequiredException;
import com.pesaguard.backend.common.exception.OrganizationSelectionException;
import com.pesaguard.backend.common.exception.TooManyRequestsException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Matches the constraint PostgreSQL names when it rejects a write, e.g.
     * {@code duplicate key value violates unique constraint "organization_memberships_org_email_key"}.
     */
    private static final java.util.regex.Pattern CONSTRAINT_IN_MESSAGE =
            java.util.regex.Pattern.compile("constraint \"([a-z0-9_]+)\"");
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Authorization denials return 403, never 500.
     *
     * <p>Spring Security 6 throws {@code AuthorizationDeniedException} from method
     * security, which is not an {@code AccessDeniedException}. With no handler for
     * it, the catch-all {@code Exception} handler answered 500 and logged the
     * failure as an unhandled error.
     *
     * <p>That is wrong in both directions. A caller who is correctly refused access
     * is told the server broke, which invites a retry against a control that is
     * working as designed; and every ordinary authorization denial pollutes the
     * error log and error-rate metric as though the platform were failing. An
     * attacker probing for privilege escalation would also be indistinguishable
     * from genuine faults in alerting.
     */
    @ExceptionHandler({
            org.springframework.security.authorization.AuthorizationDeniedException.class,
            org.springframework.security.access.AccessDeniedException.class
    })
    ResponseEntity<ApiErrorResponse> handleAccessDenied(RuntimeException exception, HttpServletRequest request) {
        log.info("Access denied requestId={} path={}", RequestContext.requestId(request), request.getRequestURI());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response(
                "ACCESS_DENIED", "You do not have access to this resource.", request, List.of()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ApiErrorResponse> handleResponseStatus(
            ResponseStatusException exception,
            HttpServletRequest request) {
        log.info("Request rejected requestId={} status={} path={}",
                RequestContext.requestId(request), exception.getStatusCode().value(), request.getRequestURI());
        return ResponseEntity.status(exception.getStatusCode()).body(response(
                "REQUEST_REJECTED", publicMessage(exception.getStatusCode().value(), "REQUEST_REJECTED"),
                request,
                List.of()));
    }

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ApiErrorResponse> handleBusiness(
            BusinessException exception,
            HttpServletRequest request) {
        if (exception instanceof TooManyRequestsException rateLimit) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.RETRY_AFTER, Long.toString(rateLimit.retryAfterSeconds()))
                    .body(response(exception.code(),
                            publicMessage(exception.status().value(), exception.code()), request, List.of()));
        }
        if (exception instanceof EmailVerificationRequiredException verification) {
            return ResponseEntity.status(exception.status()).body(new ApiErrorResponse(new ApiError(
                    publicCode(exception.code()), publicMessage(exception.status().value(), exception.code()), RequestContext.requestId(request),
                    Instant.now(), List.of(), List.of(), verification.verificationExpiresAt(),
                    verification.verificationResendAvailableAt(), verification.verificationEmail())));
        }
        if (exception instanceof MfaEnrollmentRequiredException enrollment) {
            return ResponseEntity.status(exception.status())
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .body(new ApiErrorResponse(new ApiError(
                            publicCode(exception.code()), publicMessage(exception.status().value(), exception.code()), RequestContext.requestId(request),
                            Instant.now(), List.of(), List.of(), null, null, null,
                            enrollment.enrollmentToken(), enrollment.expiresAt())));
        }
        if (exception instanceof LoginEmailMfaRequiredException challenge) {
            return ResponseEntity.status(exception.status())
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .body(new ApiErrorResponse(new ApiError(
                            publicCode(exception.code()), publicMessage(exception.status().value(), exception.code()), RequestContext.requestId(request),
                            Instant.now(), List.of(), List.of(), null, null, null, null, null,
                            challenge.challengeId(), challenge.expiresAt(),
                            challenge.resendAvailableAt(), challenge.maskedEmail())));
        }
        if (exception instanceof OrganizationSelectionException selection) {
            // The chooser travels with the challenge. A 409 naming only the problem
            // leaves the client unable to render a picker, which is the difference
            // between a user choosing a workspace and being unable to sign in at all.
            return ResponseEntity.status(exception.status()).body(new ApiErrorResponse(new ApiError(
                    publicCode(exception.code()), publicMessage(exception.status().value(), exception.code()), RequestContext.requestId(request),
                    Instant.now(), List.of(), selection.selectable().stream()
                            .map(option -> new ApiError.SelectableOrganization(
                                    option.id(), option.name(), option.slug(), option.role()))
                            .toList())));
        }
        return ResponseEntity.status(exception.status())
                .body(response(exception.code(),
                        publicMessage(exception.status().value(), exception.code()), request, List.of()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiErrorResponse> handleValidation(
            MethodArgumentNotValidException exception,
            HttpServletRequest request) {
        List<FieldViolation> violations = exception.getBindingResult().getFieldErrors().stream()
                .map(ignored -> toViolation())
                .toList();
        return ResponseEntity.badRequest()
                .body(response("VALIDATION_FAILED", "Request validation failed.", request, violations));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ApiErrorResponse> handleConstraintViolation(
            ConstraintViolationException exception,
            HttpServletRequest request) {
        List<FieldViolation> violations = exception.getConstraintViolations().stream()
                .map(ignored -> toViolation())
                .toList();
        return ResponseEntity.badRequest()
                .body(response("VALIDATION_FAILED", "Request validation failed.", request, violations));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiErrorResponse> handleUnreadable(
            HttpMessageNotReadableException exception,
            HttpServletRequest request) {
        return ResponseEntity.badRequest().body(response(
                "MALFORMED_REQUEST", "The request body is malformed.", request, List.of()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiErrorResponse> handleConflict(
            DataIntegrityViolationException exception,
            HttpServletRequest request) {
        log.warn("Data integrity conflict requestId={} type={} constraint={}",
                RequestContext.requestId(request), exception.getClass().getSimpleName(),
                constraintName(exception));
        return ResponseEntity.status(HttpStatus.CONFLICT).body(response(
                "RESOURCE_CONFLICT", "The request conflicts with an existing resource.", request, List.of()));
    }

    /**
     * Names the violated constraint so an operator can look it up.
     *
     * <p>PostgreSQL reports which constraint failed, but only in the message: the
     * exception type alone ("DataIntegrityViolationException") is identical for a
     * duplicate email, a foreign-key miss and a check failure, so a production
     * incident would otherwise be undiagnosable without a database log.
     */
    private static String constraintName(DataIntegrityViolationException exception) {
        Throwable cause = exception;
        while (cause != null) {
            String message = cause.getMessage();
            if (message != null) {
                java.util.regex.Matcher matcher = CONSTRAINT_IN_MESSAGE.matcher(message);
                if (matcher.find()) {
                    return matcher.group(1);
                }
            }
            cause = cause.getCause() == cause ? null : cause.getCause();
        }
        return "unknown";
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ResponseEntity<ApiErrorResponse> handleOptimisticLock(
            ObjectOptimisticLockingFailureException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(response(
                "CONCURRENT_UPDATE", "The resource changed concurrently. Retry with fresh data.", request, List.of()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiErrorResponse> handleNoResource(
            NoResourceFoundException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response(
                "ENDPOINT_NOT_FOUND", "The requested endpoint does not exist.", request, List.of()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiErrorResponse> handleUnexpected(Exception exception, HttpServletRequest request) {
        log.error("Unhandled request failure requestId={} type={} diagnostic={}",
                RequestContext.requestId(request), exception.getClass().getName(),
                SafeExceptionDiagnostics.stackTrace(exception));
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response(
                "INTERNAL_ERROR", "The request could not be completed.", request, List.of()));
    }

    private FieldViolation toViolation() {
        return new FieldViolation("request", "Please check the information you entered.");
    }

    private ApiErrorResponse response(
            String code,
            String message,
            HttpServletRequest request,
            List<FieldViolation> violations) {
        return new ApiErrorResponse(new ApiError(
                publicCode(code),
                message,
                RequestContext.requestId(request),
                Instant.now(),
                violations));
    }

    private static String publicCode(String code) {
        return code != null && code.matches("[A-Z][A-Z0-9_]{1,63}")
                ? code : "REQUEST_FAILED";
    }

    private static String publicMessage(int status, String code) {
        return switch (publicCode(code)) {
            case "INVALID_CREDENTIALS", "LOGIN_FAILED" ->
                "We couldn't sign you in with those details.";
            case "EMAIL_NOT_VERIFIED" -> "Please verify your email to continue.";
            case "MFA_REQUIRED", "LOGIN_EMAIL_MFA_REQUIRED" ->
                "Please verify your identity to continue.";
            case "MFA_ENROLLMENT_REQUIRED" ->
                "Set up additional sign-in protection to continue.";
            case "ORGANIZATION_SELECTION_REQUIRED" -> "Choose a workspace to continue.";
            case "RATE_LIMITED" -> "You're sending requests too quickly. Please try again shortly.";
            case "USERNAME_REQUIRED" -> "Enter a username to create your account.";
            case "USERNAME_INVALID" ->
                "Username must be 6 to 25 lowercase letters (a-z) and cannot be an email address.";
            case "USERNAME_ALREADY_TAKEN" -> "That username is already in use. Choose another one.";
            case "PHONE_ALREADY_REGISTERED" ->
                "This phone number is already in use. Use a different number or sign in.";
            case "EMAIL_ALREADY_REGISTERED" ->
                "An account already exists for this email. Sign in or reset your password.";
            case "REGISTRATION_DISABLED" -> "New account registration is currently unavailable.";
            case "RESOURCE_CONFLICT" -> "This account or resource already exists. Check your details and try again.";
            default -> switch (status) {
                case 400 -> "We couldn't process that request.";
                case 401 -> "Please sign in to continue.";
                case 403 -> "You don't have permission to perform this action.";
                case 404 -> "We couldn't find what you're looking for.";
                case 409 -> "This action conflicts with existing information.";
                case 422 -> "Please check the information you entered.";
                case 429 -> "You're sending requests too quickly. Please try again shortly.";
                case 502 -> "We couldn't complete the request right now. Please try again.";
                case 503 -> "This service is temporarily unavailable.";
                case 504 -> "The request took too long to complete. Please try again.";
                default -> status >= 500
                        ? "Something went wrong. Please try again."
                        : "We couldn't complete your request.";
            };
        };
    }
}
