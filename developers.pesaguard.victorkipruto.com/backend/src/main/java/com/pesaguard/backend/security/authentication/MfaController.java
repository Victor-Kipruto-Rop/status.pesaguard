package com.pesaguard.backend.security.authentication;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import jakarta.servlet.http.HttpServletRequest;

import com.pesaguard.backend.common.api.ApiResponse;
import com.pesaguard.backend.common.api.RequestContext;
import com.pesaguard.backend.common.exception.BusinessException;
import com.pesaguard.backend.audit.application.AuditService;
import com.pesaguard.backend.member.application.PasswordRecoveryService;
import com.pesaguard.backend.organization.application.OrganizationSecuritySettingsService;
import com.pesaguard.backend.security.principals.AuthenticatedUser;

import jakarta.validation.Valid;

/**
 * MFA enrolment, confirmation and removal.
 *
 * <p>Every route here requires an authenticated session. Enrolment is therefore
 * only reachable by someone who already holds a valid access token, which is what
 * keeps a stolen password from silently attaching an attacker-controlled factor
 * to an account.
 *
 * <p>The unconfirmed-secret gate lives in {@link MfaService}: {@code /enroll}
 * stores a factor that cannot yet satisfy a login challenge, so an enrolment that
 * is never confirmed is inert rather than a working backdoor.
 */
@RestController
@RequestMapping("/api/v1/auth/mfa")
public class MfaController {

    private final MfaService mfaService;
    private final PasswordRecoveryService passwordRecoveryService;
    private final AuditService auditService;
    private final OrganizationSecuritySettingsService organizationSecuritySettingsService;
    private final LoginService loginService;

    public MfaController(MfaService mfaService, PasswordRecoveryService passwordRecoveryService,
            AuditService auditService, OrganizationSecuritySettingsService organizationSecuritySettingsService,
            LoginService loginService) {
        this.mfaService = mfaService;
        this.passwordRecoveryService = passwordRecoveryService;
        this.auditService = auditService;
        this.organizationSecuritySettingsService = organizationSecuritySettingsService;
        this.loginService = loginService;
    }

    @GetMapping("/status")
    ApiResponse<Map<String, Object>> status(@AuthenticationPrincipal AuthenticatedUser principal) {
        return ApiResponse.of(Map.of(
                "enabled", mfaService.isEnabled(principal.userId()),
                "organizationRequired", organizationSecuritySettingsService
                        .requiresMfa(
                                organizationSecuritySettingsService.getOrCreate(principal.organizationId()),
                                organizationRole(principal))));
    }

    /**
     * Begins enrolment and returns the secret and scan URI.
     *
     * <p>{@code no-store}: this is the only response that ever contains the
     * plaintext secret, and it must not be cached anywhere.
     *
     * <p>Deliberately does <em>not</em> activate the factor. The stored secret is
     * unconfirmed until {@code /confirm} succeeds, so an enrolment that is never
     * completed is inert rather than a working factor nobody proved they hold.
     */
    @PostMapping("/enroll")
    ResponseEntity<ApiResponse<MfaEnrolmentResponse>> enroll(
            @AuthenticationPrincipal AuthenticatedUser principal) {
        if (principal.mfaEnrollmentOnly() && mfaService.isEnabled(principal.userId())) {
            throw new BusinessException(HttpStatus.CONFLICT, "MFA_ALREADY_ENABLED",
                    "Sign in with your existing authenticator to continue.");
        }
        MfaService.Enrolment enrolment = mfaService.begin(
                principal.userId(), "PesaGuard", loginService.mfaEnrollmentAccountLabel(principal));
        return ResponseEntity.status(201)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.of(new MfaEnrolmentResponse(
                        enrolment.secret(), enrolment.provisioningUri())));
    }

    /**
     * Confirms enrolment with a code from the new factor.
     *
     * @return the recovery codes, shown exactly once; only hashes are kept
     */
    @PostMapping("/confirm")
    ResponseEntity<ApiResponse<BackupCodesResponse>> confirm(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody MfaCodeRequest request,
            HttpServletRequest servletRequest) {
        List<String> codes;
        try {
            codes = mfaService.confirm(principal.userId(), request.code());
        } catch (BusinessException exception) {
            if ("MFA_INVALID".equals(exception.code())) {
                loginService.recordMfaEnrollmentFailure(principal, servletRequest.getRemoteAddr());
            }
            throw exception;
        }
        loginService.revokeCompletedMfaEnrollmentChallenge(principal);
        auditService.append(principal.organizationId(), principal.userId(), "auth.mfa.enrolled",
                "mfa", principal.userId().toString(), RequestContext.currentRequestId(), Map.of());
        return ResponseEntity.status(201)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.of(new BackupCodesResponse(codes, null)));
    }

    /**
     * Replaces the current recovery codes after proving possession of the
     * authenticator or an unused recovery code. Plaintext is never cached.
     */
    @PostMapping("/recovery-codes")
    ResponseEntity<ApiResponse<BackupCodesResponse>> regenerateRecoveryCodes(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody MfaCodeRequest request) {
        List<String> codes = mfaService.regenerateBackupCodes(principal.userId(), request.code());
        auditService.append(principal.organizationId(), principal.userId(),
                "auth.mfa.recovery_codes.regenerated", "mfa", principal.userId().toString(),
                RequestContext.currentRequestId(), Map.of());
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.of(new BackupCodesResponse(codes)));
    }

    /** Removes the factor only after fresh password and, when enabled, MFA proof. */
    @PostMapping("/disable")
    ResponseEntity<Void> disable(@AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody MfaDisableRequest request) {
        var settings = organizationSecuritySettingsService.getOrCreate(principal.organizationId());
        if (organizationSecuritySettingsService.requiresMfa(settings, organizationRole(principal))) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "MFA_REQUIRED_BY_POLICY",
                    "This workspace requires multi-factor authentication.");
        }
        passwordRecoveryService.requireCurrentPassword(principal.userId(), request.currentPassword());
        boolean wasEnabled = mfaService.isEnabled(principal.userId());
        if (wasEnabled && (request.code() == null || request.code().isBlank()
                || !mfaService.verify(principal.userId(), request.code()))) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "MFA_INVALID",
                    "The verification code is incorrect or has expired.");
        }
        mfaService.disable(principal.userId());
        auditService.append(principal.organizationId(), principal.userId(), "auth.mfa.disabled",
                "mfa", principal.userId().toString(), RequestContext.currentRequestId(),
                Map.of("wasEnabled", wasEnabled));
        return ResponseEntity.noContent().build();
    }

    private com.pesaguard.backend.organization.domain.OrganizationRole organizationRole(
            AuthenticatedUser principal) {
        return java.util.Arrays.stream(com.pesaguard.backend.organization.domain.OrganizationRole.values())
                .filter(role -> principal.authorities().contains("ROLE_" + role.name()))
                .findFirst()
                .orElse(com.pesaguard.backend.organization.domain.OrganizationRole.DEVELOPER);
    }
}