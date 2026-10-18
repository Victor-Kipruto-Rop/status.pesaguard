package com.pesaguard.backend.platform;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.pesaguard.backend.common.api.ApiError;
import com.pesaguard.backend.common.api.ApiErrorResponse;
import com.pesaguard.backend.common.api.RequestContext;
import com.pesaguard.backend.config.DeveloperRuntimeProperties;

import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class PlatformRuntimeFilter extends OncePerRequestFilter {
    private final DeveloperRuntimeProperties properties;
    private final ObjectMapper objectMapper;

    public PlatformRuntimeFilter(DeveloperRuntimeProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Bean
    FilterRegistrationBean<PlatformRuntimeFilter> platformRuntimeFilterRegistration(PlatformRuntimeFilter filter) {
        FilterRegistrationBean<PlatformRuntimeFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        String feature = featureFor(path);
        if (feature != null && !properties.isFeatureEnabled(feature)) {
            writeError(request, response, 404, "FEATURE_UNAVAILABLE", "This platform feature is currently unavailable.");
            return;
        }

        if (properties.isMaintenanceMode() && isMutation(request.getMethod()) && !maintenanceExemption(path)) {
            writeError(request, response, 503, "MAINTENANCE_MODE",
                    "This service is temporarily unavailable. Please try again shortly.");
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean isMutation(String method) {
        return !HttpMethod.GET.matches(method) && !HttpMethod.HEAD.matches(method)
                && !HttpMethod.OPTIONS.matches(method) && !HttpMethod.TRACE.matches(method);
    }

    private static boolean maintenanceExemption(String path) {
        return path.startsWith("/api/v1/auth/login") || path.startsWith("/api/v1/auth/register")
                || path.startsWith("/api/v1/auth/refresh") || path.startsWith("/api/v1/auth/verify-email")
                || path.startsWith("/api/v1/auth/forgot-password") || path.startsWith("/api/v1/auth/reset-password")
                || path.startsWith("/api/v1/auth/logout") || path.startsWith("/api/v1/auth/passkeys/login/")
                || path.startsWith("/api/v1/auth/passkeys/mfa/")
                || path.startsWith("/api/v1/invitations/")
                || path.startsWith("/api/v1/billing/webhooks/")
                || path.equals("/api/v1/support/tickets");
    }

    private static String featureFor(String path) {
        if (under(path, "/api/v1/webhooks") || path.equals("/api/v1/exports/webhook-deliveries")) return "webhooks";
        if (under(path, "/api/v1/events")) return "events";
        if (under(path, "/api/v1/usage")
                || path.equals("/api/v1/exports/usage") || path.equals("/api/v1/exports/logs")) return "usage";
        if (under(path, "/api/v1/oauth")) return "oauth";
        if (under(path, "/api/v1/sandboxes") || under(path, "/api/v1/sandbox")) return "sandbox";
        if (under(path, "/api/v1/production-access")) return "productionAccess";
        if (under(path, "/api/v1/support")) return "support";
        if (under(path, "/api/v1/notifications")) return "notifications";
        if (under(path, "/api/v1/billing") && !under(path, "/api/v1/billing/webhooks")) return "billing";
        if (under(path, "/api/v1/audit-events") || under(path, "/api/v1/security-events")) return "audit";
        if (path.equals("/api/v1/exports/organization")) return "organizationExport";
        return null;
    }

    private static boolean under(String path, String base) {
        return path.equals(base) || path.startsWith(base + "/");
    }

    private void writeError(HttpServletRequest request, HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        ApiError error = new ApiError(code, message, RequestContext.requestId(request), Instant.now(), List.of());
        objectMapper.writeValue(response.getOutputStream(), new ApiErrorResponse(error));
    }
}
