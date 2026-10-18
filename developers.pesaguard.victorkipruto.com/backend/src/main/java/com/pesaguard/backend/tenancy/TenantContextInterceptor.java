package com.pesaguard.backend.tenancy;

import java.util.Map;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class TenantContextInterceptor implements HandlerInterceptor {

    private static final String PREVIOUS_CONTEXT_ATTRIBUTE =
            TenantContextInterceptor.class.getName() + ".previousContext";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        TenantContext previous = TenantContextHolder.current().orElse(null);
        if (previous == null) {
            return true;
        }

        request.setAttribute(PREVIOUS_CONTEXT_ATTRIBUTE, previous);
        Map<String, String> variables = uriTemplateVariables(request);
        UUID projectId = canonicalUuid(variables.get("projectId"));
        UUID environmentId = canonicalUuid(variables.get("environmentId"));
        TenantContextHolder.set(new TenantContext(
                previous.tenantId(), previous.organizationId(), previous.actorId(), previous.sessionId(),
                projectId, environmentId, previous.requestId(), previous.remoteAddress(), previous.userAgent()));
        updateResourceMdc(projectId, environmentId);
        return true;
    }

    @Override
    public void afterCompletion(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler,
            Exception exception) {
        Object previous = request.getAttribute(PREVIOUS_CONTEXT_ATTRIBUTE);
        if (previous instanceof TenantContext context) {
            TenantContextHolder.set(context);
            updateResourceMdc(context.projectId(), context.environmentId());
            request.removeAttribute(PREVIOUS_CONTEXT_ATTRIBUTE);
        }
    }

    private static Map<String, String> uriTemplateVariables(HttpServletRequest request) {
        Object value = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (value instanceof Map<?, ?> variables) {
            return variables.entrySet().stream()
                    .filter(entry -> entry.getKey() instanceof String && entry.getValue() instanceof String)
                    .collect(java.util.stream.Collectors.toMap(
                            entry -> (String) entry.getKey(),
                            entry -> (String) entry.getValue()));
        }
        return Map.of();
    }

    private static UUID canonicalUuid(String value) {
        if (value == null) {
            return null;
        }
        try {
            UUID parsed = UUID.fromString(value);
            return parsed.toString().equals(value) ? parsed : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static void updateResourceMdc(UUID projectId, UUID environmentId) {
        MDC.remove("project_id");
        MDC.remove("environment_id");
        if (projectId != null) {
            MDC.put("project_id", projectId.toString());
        }
        if (environmentId != null) {
            MDC.put("environment_id", environmentId.toString());
        }
    }
}
