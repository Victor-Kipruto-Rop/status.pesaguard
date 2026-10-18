package com.pesaguard.backend.common.api;

import java.io.IOException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Security response headers.
 *
 * <p>Applies to every response, including error responses. A header added only on
 * the success path is absent exactly when something has gone wrong, which is when
 * an attacker is looking at it.
 *
 * <p>Each header is here for a specific attack:
 *
 * <ul>
 *   <li>{@code X-Content-Type-Options: nosniff} — stops a browser reinterpreting a
 *       JSON error body as HTML. Without it, a response containing
 *       {@code {"error":"<script>"}} can execute.</li>
 *   <li>{@code X-Frame-Options: DENY} — clickjacking. The portal is the one place
 *       an authenticated user looks at credentials, so it is the target.</li>
 *   <li>{@code Cache-Control: no-store} — a cached usage or credential response
 *       survives logout and is readable from a shared machine. This is the header
 *       most often missing on an API that returns billing-shaped data.</li>
 * </ul>
 *
 * <p><b>HSTS is conditional.</b> Sending it over plain HTTP is meaningless at
 * best and, in a development environment where the host is not the production
 * name, actively harmful: the browser would refuse to return to the host for a
 * year. It is therefore enabled only when configured, which is expected to mean
 * "we terminate TLS here".
 *
 * <p>No Content-Security-Policy is set. This is a JSON API, and a CSP written for
 * a document would be cargo-culted here without protecting anything. The portal
 * is a separate application and should carry its own.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class SecureHeadersFilter extends OncePerRequestFilter {

    private final boolean hstsEnabled;
    private final int hstsMaxAgeSeconds;

    public SecureHeadersFilter(
            @Value("${pesaguard.security.hsts-enabled:false}") boolean hstsEnabled,
            @Value("${pesaguard.security.hsts-max-age-seconds:31536000}")
                    int hstsMaxAgeSeconds) {
        this.hstsEnabled = hstsEnabled;
        this.hstsMaxAgeSeconds = hstsMaxAgeSeconds;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        apply(response);
        filterChain.doFilter(request, response);
    }

    void apply(HttpServletResponse response) {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Cross-Origin-Opener-Policy", "same-origin");
        response.setHeader("Cross-Origin-Resource-Policy", "same-origin");
        // A referrer must never carry a path segment that might contain an
        // identifier.
        response.setHeader("Permissions-Policy", "geolocation=(), microphone=(), camera=()");
        // Usage and credential responses are customer data; a cached copy outlives
        // the session that was allowed to read it.
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Pragma", "no-cache");

        if (hstsEnabled) {
            response.setHeader("Strict-Transport-Security",
                    "max-age=" + hstsMaxAgeSeconds + "; includeSubDomains");
        }
    }
}