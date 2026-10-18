package com.pesaguard.backend.security.sessions;

import java.io.IOException;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.pesaguard.backend.common.exception.BusinessException;
import com.pesaguard.backend.onboarding.application.DeveloperOnboardingStatusService;
import com.pesaguard.backend.security.principals.AuthenticatedUser;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Rechecks developer account and organization access before dashboard APIs. */
@Component
public class DeveloperWorkspaceAccessFilter extends OncePerRequestFilter {

    private final DeveloperOnboardingStatusService onboardingStatusService;
    private final RestAccessDeniedHandler accessDeniedHandler;

    public DeveloperWorkspaceAccessFilter(
            DeveloperOnboardingStatusService onboardingStatusService,
            RestAccessDeniedHandler accessDeniedHandler) {
        this.onboardingStatusService = onboardingStatusService;
        this.accessDeniedHandler = accessDeniedHandler;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return !path.startsWith("/api/v1/")
                || "OPTIONS".equals(request.getMethod())
                || path.startsWith("/api/v1/auth/")
                || path.startsWith("/api/v1/onboarding/")
                || path.startsWith("/api/v1/account/")
                || path.equals("/api/v1/platform/status")
                || path.startsWith("/api/v1/status")
                || path.startsWith("/api/v1/invitations/")
                || path.equals("/api/v1/support")
                || path.startsWith("/api/v1/support/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser developer)) {
            filterChain.doFilter(request, response);
            return;
        }

        if (developer.serviceAccount() && !serviceAccountRouteAllowed(request.getServletPath())) {
            accessDeniedHandler.handle(request, response,
                    new AccessDeniedException("Service accounts cannot access developer identity or administration APIs."));
            return;
        }

        try {
            onboardingStatusService.statusFor(developer);
        } catch (BusinessException exception) {
            accessDeniedHandler.handle(request, response, new AccessDeniedException(exception.getMessage()));
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean serviceAccountRouteAllowed(String path) {
        return path.equals("/api/v1/projects") || path.startsWith("/api/v1/projects/")
                || path.equals("/api/v1/environments") || path.startsWith("/api/v1/environments/")
                || path.equals("/api/v1/sandboxes") || path.startsWith("/api/v1/sandboxes/")
                || path.startsWith("/api/v1/usage")
                || path.startsWith("/api/v1/audit-events")
                || path.equals("/api/v1/oauth/applications")
                || path.startsWith("/api/v1/oauth/applications/");
    }
}
