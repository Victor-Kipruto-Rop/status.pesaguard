package com.pesaguard.backend.security.sessions;

import java.io.IOException;
import java.util.Optional;

import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import com.pesaguard.backend.security.principals.AuthenticatedUser;
import com.pesaguard.backend.security.tokens.AccessTokenService;
import com.pesaguard.backend.security.tokens.RevokedTokenRegistry;
import com.pesaguard.backend.serviceaccount.application.ServiceAccountService;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Establishes the principal from an RS256 access token.
 *
 * <p>Stateless by design: the token's signature, expiry, issuer and audience are
 * checked in-process, and session rows are not read to authenticate requests.
 * Activity is written separately at a bounded interval, not used to accept or
 * reject the token. The short access-token lifetime is why a stolen token has a
 * limited window even without a session lookup.
 *
 * <p>Revocation is handled by two mechanisms rather than by a lookup here:
 * logout and account actions revoke the refresh-token family, which ends the
 * session for every subsequent refresh, and {@link RevokedTokenRegistry} is the
 * emergency backstop for a token that must stop working immediately.
 *
 * <p>Any token this filter cannot vouch for results in an identical 401. The
 * reasons differ — malformed, wrong algorithm, bad signature, expired, revoked,
 * or no token at all — but collapsing them means a caller cannot use the response
 * to learn which part of a forged token was wrong.
 */
@Component
public class SessionAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(SessionAuthenticationFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";
    private static final int MAX_TOKEN_LENGTH = 4096;
    private static final String SANDBOX_KEY_VERIFICATION_PATH = "/api/v1/sandbox/transactions";
    private static final String API_KEY_DATA_PATH_PREFIX = "/api/v1/key-data/";

    private final AccessTokenService accessTokenService;
    private final RevokedTokenRegistry revokedTokens;
    private final RestAuthenticationEntryPoint entryPoint;
    private final RestAccessDeniedHandler accessDeniedHandler;
    private final ServiceAccountService serviceAccountService;
    private final SessionActivityRecorder sessionActivityRecorder;

    public SessionAuthenticationFilter(
            AccessTokenService accessTokenService,
            RevokedTokenRegistry revokedTokens,
            RestAuthenticationEntryPoint entryPoint,
            RestAccessDeniedHandler accessDeniedHandler,
            ServiceAccountService serviceAccountService,
            SessionActivityRecorder sessionActivityRecorder) {
        this.accessTokenService = accessTokenService;
        this.revokedTokens = revokedTokens;
        this.entryPoint = entryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
        this.serviceAccountService = serviceAccountService;
        this.sessionActivityRecorder = sessionActivityRecorder;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return ("GET".equals(request.getMethod())
                && SANDBOX_KEY_VERIFICATION_PATH.equals(request.getServletPath()))
                || ("GET".equals(request.getMethod())
                        && request.getServletPath().startsWith(API_KEY_DATA_PATH_PREFIX))
                || ("POST".equals(request.getMethod())
                        && ("/api/v1/service-accounts/token".equals(request.getServletPath())
                                || request.getServletPath().startsWith("/api/v1/billing/webhooks/")));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        if (!StringUtils.hasText(authorization)) {
            filterChain.doFilter(request, response);
            return;
        }
        if (!authorization.startsWith(BEARER_PREFIX)
                || authorization.length() <= BEARER_PREFIX.length()
                || authorization.length() > MAX_TOKEN_LENGTH) {
            SecurityContextHolder.clearContext();
            entryPoint.commence(request, response, new BadBearerTokenException());
            return;
        }

        String token = authorization.substring(BEARER_PREFIX.length()).trim();
        if (token.isEmpty()) {
            entryPoint.commence(request, response, new BadBearerTokenException());
            return;
        }

        Optional<AuthenticatedUser> principal = accessTokenService.verify(token)
                .or(() -> serviceAccountService.authenticateAccessToken(token));
        if (principal.isEmpty() || (!principal.get().serviceAccount()
                && revokedTokens.isRevoked(principal.get().sessionId()))) {
            // Revoked and invalid are reported identically, so the response cannot
            // be used to distinguish a live session from a dead one.
            SecurityContextHolder.clearContext();
            entryPoint.commence(request, response, new BadBearerTokenException());
            return;
        }

        AuthenticatedUser user = principal.get();
        if (user.mfaEnrollmentOnly()
                && !isMfaEnrollmentRoute(request.getMethod(), request.getServletPath())) {
            SecurityContextHolder.clearContext();
            accessDeniedHandler.handle(request, response,
                    new org.springframework.security.access.AccessDeniedException(
                            "MFA enrolment is required before using this session."));
            return;
        }
        try {
            sessionActivityRecorder.record(user);
        } catch (RuntimeException unavailable) {
            log.warn("Could not update activity for session {}; session remains authenticated",
                    user.sessionId(), unavailable);
        }
        UsernamePasswordAuthenticationToken authentication = UsernamePasswordAuthenticationToken.authenticated(
                user,
                null,
                user.authorities().stream().map(SimpleGrantedAuthority::new).toList());
        SecurityContextHolder.getContext().setAuthentication(authentication);
        filterChain.doFilter(request, response);
    }

    private boolean isMfaEnrollmentRoute(String method, String path) {
        return ("GET".equals(method) && "/api/v1/auth/mfa/status".equals(path))
                || ("POST".equals(method) && ("/api/v1/auth/mfa/enroll".equals(path)
                        || "/api/v1/auth/mfa/confirm".equals(path)));
    }

    private static final class BadBearerTokenException extends AuthenticationException {
        private BadBearerTokenException() {
            super("The bearer token is invalid or expired");
        }
    }
}
