package com.pesaguard.backend.oauth.api;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pesaguard.backend.common.api.ApiResponse;
import com.pesaguard.backend.oauth.application.OAuthTokenService;
import com.pesaguard.backend.security.principals.AuthenticatedUser;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/**
 * The authorization-code flow endpoints.
 *
 * <p>The authorize, token, revoke and introspect endpoints authenticate the client
 * itself and are reachable without a developer-portal session. The consent
 * endpoints act on the signed-in resource owner and do require one.
 *
 * <p>Client credentials are read from HTTP Basic authentication. They are never
 * accepted as query parameters: URLs end up in access logs, proxy logs and browser
 * history, and a client secret must not be written to any of them.
 */
@RestController
@RequestMapping("/api/v1/oauth")
public class OAuthController {

    private final OAuthTokenService tokenService;

    public OAuthController(OAuthTokenService tokenService) {
        this.tokenService = tokenService;
    }

    @PostMapping("/authorize")
    ApiResponse<ConsentRequestView> authorize(@AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody AuthorizationRequestBody body, HttpServletRequest request) {
        return ApiResponse.of(tokenService.authorize(principal, body, request.getRemoteAddr()));
    }

    @GetMapping("/consents")
    ApiResponse<List<ConsentRequestView>> pendingConsents(@AuthenticationPrincipal AuthenticatedUser principal) {
        return ApiResponse.of(tokenService.pendingConsents(principal));
    }

    /** The only path that issues an authorization code. */
    @PostMapping("/consents/{consentId}/approve")
    ResponseEntity<ApiResponse<AuthorizationApprovalView>> approve(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID consentId) {
        return ResponseEntity.status(201)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.of(tokenService.approve(principal, consentId)));
    }

    @PostMapping("/consents/{consentId}/deny")
    ResponseEntity<Void> deny(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID consentId) {
        tokenService.deny(principal, consentId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/token")
    ResponseEntity<ApiResponse<TokenResponseView>> token(
            @Valid @RequestBody TokenRequestBody body, HttpServletRequest request) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.of(tokenService.token(body, request.getRemoteAddr())));
    }

    @PostMapping("/revoke")
    ResponseEntity<Void> revoke(@RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestParam(required = false) String token,
            HttpServletRequest request) {
        ClientCredentials credentials = ClientCredentials.fromBasic(authorization);
        throttleGuard(request);
        tokenService.revoke(credentials.clientId(), credentials.clientSecret(), token);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/introspect")
    ApiResponse<IntrospectionResponseView> introspect(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestParam(required = false) String token,
            HttpServletRequest request) {
        ClientCredentials credentials = ClientCredentials.fromBasic(authorization);
        throttleGuard(request);
        return ApiResponse.of(tokenService.introspect(credentials.clientId(), credentials.clientSecret(), token));
    }

    /** Preserves the same per-IP budget the token endpoint enforces. */
    private void throttleGuard(HttpServletRequest request) {
        tokenService.enforceClientThrottle(request.getRemoteAddr());
    }
}