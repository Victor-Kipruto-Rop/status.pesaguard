package com.pesaguard.backend.tenancy;

import java.io.IOException;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.pesaguard.backend.common.api.RequestContext;
import com.pesaguard.backend.security.principals.AuthenticatedUser;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class TenantContextFilter extends OncePerRequestFilter {

    private static final String TENANT_ID = "tenant_id";
    private static final String ORGANIZATION_ID = "organization_id";
    private static final String ACTOR_ID = "actor_id";
    private static final String SESSION_ID = "session_id";
    private static final String PROJECT_ID = "project_id";
    private static final String ENVIRONMENT_ID = "environment_id";
    private static final String CORRELATION_ID = "correlation_id";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            TenantContextHolder.clear();
            clearMdc();
            filterChain.doFilter(request, response);
            return;
        }

        TenantContext context = new TenantContext(
                user.organizationId(), user.organizationId(), user.userId(), user.sessionId(),
                null, null,
                RequestContext.currentRequestId().toString(), RequestContext.currentRemoteAddress(),
                RequestContext.currentUserAgent());
        TenantContextHolder.set(context);
        putMdc(TENANT_ID, user.organizationId());
        putMdc(ORGANIZATION_ID, user.organizationId());
        putMdc(ACTOR_ID, user.userId());
        putMdc(SESSION_ID, user.sessionId());
        putMdc(PROJECT_ID, context.projectId());
        putMdc(ENVIRONMENT_ID, context.environmentId());
        putMdc(CORRELATION_ID, context.requestId());
        try {
            filterChain.doFilter(request, response);
        } finally {
            TenantContextHolder.clear();
            clearMdc();
        }
    }

    private void putMdc(String key, Object value) {
        if (value != null) {
            MDC.put(key, value.toString());
        }
    }

    private void clearMdc() {
        for (String key : new String[] {TENANT_ID, ORGANIZATION_ID, ACTOR_ID, SESSION_ID,
                PROJECT_ID, ENVIRONMENT_ID, CORRELATION_ID}) {
            MDC.remove(key);
        }
    }
}
