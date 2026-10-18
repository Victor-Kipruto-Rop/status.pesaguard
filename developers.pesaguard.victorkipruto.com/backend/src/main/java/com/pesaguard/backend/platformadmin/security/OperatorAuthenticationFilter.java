package com.pesaguard.backend.platformadmin.security;

import java.io.IOException;
import java.util.Optional;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import com.pesaguard.backend.platformadmin.domain.AuthenticatedOperator;
import com.pesaguard.backend.platformadmin.domain.OperatorCapability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Authenticates internal operator requests.
 *
 * <p>Installed only in the operator filter chain, which is scoped to
 * {@code /internal/**}. It never runs on a developer route, and the developer
 * session filter never runs on an operator route.
 *
 * <p>Two properties are worth stating explicitly because they are what the whole
 * separation rests on:
 *
 * <ul>
 *   <li>it accepts <b>only</b> an operator token — a developer session cookie or
 *       bearer token is not even parsed; and</li>
 *   <li>an unauthenticated request is rejected with the same response as a
 *       forged one, so probing reveals nothing about which tokens exist.</li>
 * </ul>
 */
public class OperatorAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";
    /** Kept out of the bearer token so a reason is specific to one customer action. */
    public static final String OPERATOR_REASON_HEADER = "X-Operator-Reason";

    private final OperatorTokenService tokenService;

    public OperatorAuthenticationFilter(OperatorTokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return "GET".equals(request.getMethod())
                && OperatorSecurityConfig.HEALTH_PATH.equals(request.getServletPath());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        Optional<AuthenticatedOperator> operator = tokenService.authenticate(bearerToken(request));

        if (operator.isEmpty()) {
            // 401 with no body: an operator endpoint should not advertise itself to
            // a scanner that is unauthenticated.
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setHeader("WWW-Authenticate", "Bearer");
            return;
        }

        AuthenticatedOperator tokenOperator = operator.get();
        AuthenticatedOperator requestOperator = new AuthenticatedOperator(
                tokenOperator.operatorId(), tokenOperator.subject(), tokenOperator.capabilities(),
                request.getHeader(OPERATOR_REASON_HEADER));
        Authentication authentication = new OperatorAuthentication(requestOperator, request);
        SecurityContextHolder.getContext().setAuthentication(authentication);
        try {
            filterChain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private String bearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER)) {
            return null;
        }
        return header.substring(BEARER.length()).trim();
    }

    /** An {@link Authentication} carrying an {@link AuthenticatedOperator}. */
    static final class OperatorAuthentication extends org.springframework.security.authentication
            .AbstractAuthenticationToken {

        private final AuthenticatedOperator operator;

        OperatorAuthentication(AuthenticatedOperator operator, HttpServletRequest request) {
            super(operator.capabilities().stream()
                    .map(capability -> new SimpleGrantedAuthority(
                            "PLATFORM_" + capability.name()))
                    .toList());
            this.operator = operator;
            setAuthenticated(true);
            setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        }

        @Override
        public Object getCredentials() {
            // Never the token. A principal whose getCredentials returns a live
            // secret will eventually end up in a log or an error page.
            return null;
        }

        @Override
        public AuthenticatedOperator getPrincipal() {
            return operator;
        }

        @Override
        public String getName() {
            return operator.subject();
        }

        boolean holds(OperatorCapability capability) {
            return operator.can(capability);
        }
    }
}
