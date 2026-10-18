package com.pesaguard.backend.security.authentication;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pesaguard.backend.common.api.ApiResponse;
import com.pesaguard.backend.common.exception.ResourceNotFoundException;
import com.pesaguard.backend.member.application.EmailVerificationService;
import com.pesaguard.backend.member.application.PasswordRecoveryService;
import com.pesaguard.backend.member.application.PasswordRecoveryDeliveryService;
import com.pesaguard.backend.member.application.PasswordChangeService;
import com.pesaguard.backend.member.infrastructure.UserAccountRepository;
import com.pesaguard.backend.rbac.application.AuthorizationService;
import com.pesaguard.backend.security.principals.AuthenticatedUser;
import com.pesaguard.backend.security.sessions.AuthSession;
import com.pesaguard.backend.security.sessions.SessionQueryService;
import com.pesaguard.backend.securitycenter.domain.SessionDevice;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/**
 * Developer identity endpoints.
 *
 * <p>Access is a bearer token held by the portal; there is no cookie session, so
 * {@code csrf()} is disabled in {@code SecurityConfig}. If cookie auth is ever
 * introduced it must be {@code Secure; HttpOnly; SameSite} <em>and</em> paired
 * with CSRF protection — enabling cookies without CSRF protection is how a
 * logged-in developer's portal gets driven by a third-party page.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthenticationController {

    private final RegistrationService registrationService;
    private final LoginService loginService;
    private final PasswordRecoveryService passwordRecoveryService;
    private final PasswordChangeService passwordChangeService;
    private final PasswordRecoveryDeliveryService passwordRecoveryDeliveryService;
    private final EmailVerificationService emailVerificationService;
    private final MfaService mfaService;
    private final SessionQueryService sessionQueryService;
    private final AuthorizationService authorizationService;
    private final UserAccountRepository userAccountRepository;

    public AuthenticationController(
            RegistrationService registrationService,
            LoginService loginService,
            PasswordRecoveryService passwordRecoveryService,
            PasswordChangeService passwordChangeService,
            PasswordRecoveryDeliveryService passwordRecoveryDeliveryService,
            EmailVerificationService emailVerificationService,
            MfaService mfaService,
            SessionQueryService sessionQueryService,
            AuthorizationService authorizationService,
            UserAccountRepository userAccountRepository) {
        this.registrationService = registrationService;
        this.loginService = loginService;
        this.passwordRecoveryService = passwordRecoveryService;
        this.passwordChangeService = passwordChangeService;
        this.passwordRecoveryDeliveryService = passwordRecoveryDeliveryService;
        this.emailVerificationService = emailVerificationService;
        this.mfaService = mfaService;
        this.sessionQueryService = sessionQueryService;
        this.authorizationService = authorizationService;
        this.userAccountRepository = userAccountRepository;
    }

    @PostMapping("/register")
    ResponseEntity<ApiResponse<RegistrationResponse>> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(201)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.of(registrationService.register(request)));
    }

    @PostMapping("/login")
    ResponseEntity<ApiResponse<AuthenticationResponse>> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest servletRequest) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.of(loginService.login(request, servletRequest.getRemoteAddr(),
                SessionDevice.describe(servletRequest.getHeader("User-Agent")))));
    }

    @PostMapping("/login/email-mfa/verify")
    ResponseEntity<ApiResponse<AuthenticationResponse>> verifyLoginEmailMfa(
            @Valid @RequestBody LoginEmailMfaVerifyRequest request,
            HttpServletRequest servletRequest) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.of(loginService.completeEmailMfaLogin(
                request.challengeId(), request.code(),
                SessionDevice.describe(servletRequest.getHeader("User-Agent")),
                servletRequest.getRemoteAddr())));
    }

    @PostMapping("/login/email-mfa/resend")
    ResponseEntity<ApiResponse<EmailLoginMfaChallenge>> resendLoginEmailMfa(
            @Valid @RequestBody LoginEmailMfaResendRequest request) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.of(loginService.resendEmailMfaLogin(request.challengeId())));
    }

    @PostMapping("/switch-workspace")
    ResponseEntity<ApiResponse<AuthenticationResponse>> switchWorkspace(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody SwitchWorkspaceRequest request,
            HttpServletRequest servletRequest) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.of(loginService.switchWorkspace(principal, request.workspaceId(),
                        SessionDevice.describe(servletRequest.getHeader("User-Agent")),
                        servletRequest.getRemoteAddr())));
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(@AuthenticationPrincipal AuthenticatedUser principal) {
        loginService.logout(principal.userId(), principal.organizationId(), principal.sessionId());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/session")
    ApiResponse<CurrentSessionResponse> currentSession(@AuthenticationPrincipal AuthenticatedUser principal) {
        var account = userAccountRepository.findById(principal.userId())
                .orElseThrow(() -> new ResourceNotFoundException("User account"));
        java.util.Set<String> authorities = new java.util.HashSet<>(principal.authorities());
        authorizationService.effectivePermissions(principal).stream()
                .map(com.pesaguard.backend.rbac.domain.Permission::value)
                .forEach(authorities::add);
        return ApiResponse.of(new CurrentSessionResponse(
                principal.sessionId().toString(),
                new SessionUserResponse(principal.userId(), account.getEmail(), account.getDisplayName()),
                principal.organizationId(),
                authorities));
    }

    @GetMapping("/login-protection")
    ApiResponse<LoginService.LoginProtectionStatus> loginProtection(
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return ApiResponse.of(loginService.loginProtectionStatus(principal));
    }

    /**
     * The frontend dashboard entry point.
     *
     * <p>Identical in content to {@code /session}; {@code /me} is the name the
     * portal's flow expects, so it is served rather than making the client learn
     * a second endpoint for the same data.
     */
    @GetMapping("/me")
    ApiResponse<CurrentSessionResponse> me(@AuthenticationPrincipal AuthenticatedUser principal) {
        return currentSession(principal);
    }
    /**
     * Exchanges a refresh token for a new access session and refresh token.
     *
     * <p>Both are returned because the access token the client just presented is
     * expired by definition — that is why it came here. Returning only a new
     * refresh token would leave the client unable to make a single call.
     *
     * <p>{@code no-store} because the body carries live credentials; without it an
     * intermediary cache could serve one user's tokens to another.
     *
     * <p>A replayed token revokes the whole family and returns 401. That is the
     * intended response, not an error to be softened: the token was leaked.
     */
    @PostMapping("/refresh")
    ResponseEntity<ApiResponse<AuthenticationResponse>> refresh(
            @Valid @RequestBody RefreshRequest request,
            HttpServletRequest servletRequest) {
        AuthenticationResponse response = loginService.refresh(
                request.refreshToken(),
                SessionDevice.describe(servletRequest.getHeader("User-Agent")),
                servletRequest.getRemoteAddr());
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.of(response));
    }

    @PostMapping("/verify-email")
    ResponseEntity<ApiResponse<Map<String, Object>>> verifyEmail(@Valid @RequestBody VerifyEmailRequest request) {
        if (request.token() != null && !request.token().isBlank()) {
            emailVerificationService.confirmLegacyLink(request.token());
        } else {
            emailVerificationService.confirm(request.email(), request.code());
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.of(Map.of("verified", true)));
    }

    @PostMapping("/verify-email/complete-registration")
    ResponseEntity<ApiResponse<AuthenticationResponse>> completeRegistrationVerification(
            @Valid @RequestBody CompleteRegistrationVerificationRequest request,
            HttpServletRequest servletRequest) {
        AuthenticationResponse response = loginService.completeRegistrationVerification(
                request.email(), request.code(), request.password(),
                SessionDevice.describe(servletRequest.getHeader("User-Agent")),
                servletRequest.getRemoteAddr());
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.of(response));
    }

    @PostMapping("/verify-email/complete-registration-link")
    ResponseEntity<ApiResponse<AuthenticationResponse>> completeRegistrationVerificationByLink(
            @Valid @RequestBody CompleteRegistrationLinkRequest request,
            HttpServletRequest servletRequest) {
        AuthenticationResponse response = loginService.completeRegistrationVerificationByLink(
                request.token(),
                SessionDevice.describe(servletRequest.getHeader("User-Agent")),
                servletRequest.getRemoteAddr());
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.of(response));
    }

    @PostMapping("/verify-email/resend")
    ResponseEntity<Void> resendVerification(@Valid @RequestBody ResendEmailVerificationRequest request) {
        emailVerificationService.reissue(request.email());
        return ResponseEntity.accepted().header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }

    /**
     * Starts a password reset without revealing whether an address is registered.
     *
     * <p>The token is never returned here; it exists only so a delivery transport
     * can put it in an email.
     */
    @PostMapping("/forgot-password")
    ResponseEntity<Void> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        String token = passwordRecoveryService.request(request.email());
        if (token != null) {
            passwordRecoveryDeliveryService.send(request.email().trim().toLowerCase(java.util.Locale.ROOT), token);
        }
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/reset-password")
    ResponseEntity<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        passwordRecoveryService.complete(request.token(), request.newPassword());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/change-password/request")
    ResponseEntity<ApiResponse<PasswordChangeChallengeView>> requestPasswordChange(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody PasswordChangeStartRequest request) {
        PasswordChangeChallengeView challenge = passwordChangeService.request(
                principal.userId(), principal.sessionId(), request.currentPassword(), request.newPassword());
        return ResponseEntity.accepted()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.of(challenge));
    }

    @PostMapping("/change-password/verify")
    ResponseEntity<Void> verifyPasswordChange(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody PasswordChangeVerifyRequest request) {
        passwordChangeService.verify(principal.userId(), principal.sessionId(), request.challengeId(),
                request.code(), request.currentPassword(), request.newPassword());
        return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }

    /** The caller's live sessions, most recently active first. */
    @GetMapping("/sessions")
    ApiResponse<List<SessionSummaryResponse>> sessions(@AuthenticationPrincipal AuthenticatedUser principal) {
        List<SessionSummaryResponse> summaries = sessionQueryService.activeFor(principal).stream()
                .map(session -> toSummary(session, principal))
                .toList();
        return ApiResponse.of(summaries);
    }

    /**
     * Ends one of the caller's own sessions.
     *
     * <p>404 rather than 204 when the id is unknown or belongs to someone else,
     * so this cannot be used to test whether a session id exists.
     */
    @DeleteMapping("/sessions/{sessionId}")
    ResponseEntity<Void> revokeSession(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable java.util.UUID sessionId) {
        if (!sessionQueryService.revoke(principal, sessionId)) {
            throw new ResourceNotFoundException("Session");
        }
        return ResponseEntity.noContent().build();
    }

    /** Keeps this session and signs out every other active session on the account. */
    @PostMapping("/sessions/revoke-others")
    ApiResponse<Map<String, Integer>> revokeOtherSessions(
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return ApiResponse.of(Map.of("revokedCount", sessionQueryService.revokeOthers(principal)));
    }

    private SessionSummaryResponse toSummary(AuthSession session, AuthenticatedUser principal) {
        return new SessionSummaryResponse(
                session.getId(),
                session.getDeviceLabel() == null ? "Unknown client" : session.getDeviceLabel(),
                session.getLastIp(),
                session.getLastSeenAt(),
                session.getExpiresAt(),
                session.getId().equals(principal.sessionId()));
    }
}
