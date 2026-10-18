package com.pesaguard.backend.common.api;

import java.io.IOException;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    private static final String MDC_KEY = "request_id";
    private static final String CORRELATION_ID_MDC_KEY = "correlation_id";
    private static final String TRACEPARENT_MDC_KEY = "traceparent";
    private static final String REMOTE_ADDRESS_MDC_KEY = "remote_address";
    private static final String USER_AGENT_MDC_KEY = "user_agent";
    private static final int MAX_IP_LENGTH = 45;
    private static final int MAX_USER_AGENT_LENGTH = 512;
    private static final java.util.regex.Pattern SAFE_CORRELATION_ID =
            java.util.regex.Pattern.compile("[A-Za-z0-9._:-]{1,64}");
    private static final java.util.regex.Pattern TRACEPARENT =
            java.util.regex.Pattern.compile("(?!ff-)[0-9a-f]{2}-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}");

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        UUID requestId = parseOrCreate(request.getHeader(RequestContext.REQUEST_ID_HEADER));
        request.setAttribute(RequestContext.REQUEST_ID_ATTRIBUTE, requestId);
        response.setHeader(RequestContext.REQUEST_ID_HEADER, requestId.toString());
        String correlationId = safeCorrelationId(request.getHeader(RequestContext.CORRELATION_ID_HEADER));
        if (correlationId == null) {
            correlationId = requestId.toString();
        }
        String traceparent = safeTraceparent(request.getHeader(RequestContext.TRACEPARENT_HEADER));
        response.setHeader(RequestContext.CORRELATION_ID_HEADER, correlationId);
        if (traceparent != null) {
            response.setHeader(RequestContext.TRACEPARENT_HEADER, traceparent);
            MDC.put(TRACEPARENT_MDC_KEY, traceparent);
        }
        MDC.put(MDC_KEY, requestId.toString());
        MDC.put(CORRELATION_ID_MDC_KEY, correlationId);
        MDC.put(REMOTE_ADDRESS_MDC_KEY, truncate(request.getRemoteAddr(), MAX_IP_LENGTH));
        String userAgent = request.getHeader("User-Agent");
        if (userAgent != null) {
            MDC.put(USER_AGENT_MDC_KEY, truncate(userAgent, MAX_USER_AGENT_LENGTH));
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
            MDC.remove(CORRELATION_ID_MDC_KEY);
            MDC.remove(TRACEPARENT_MDC_KEY);
            MDC.remove(REMOTE_ADDRESS_MDC_KEY);
            MDC.remove(USER_AGENT_MDC_KEY);
        }
    }

    static UUID currentRequestId() {
        String value = MDC.get(MDC_KEY);
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    static String currentCorrelationId() {
        return MDC.get(CORRELATION_ID_MDC_KEY);
    }

    static String currentTraceparent() {
        return MDC.get(TRACEPARENT_MDC_KEY);
    }

    static String currentRemoteAddress() {
        return MDC.get(REMOTE_ADDRESS_MDC_KEY);
    }

    static String currentUserAgent() {
        return MDC.get(USER_AGENT_MDC_KEY);
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private UUID parseOrCreate(String value) {
        if (StringUtils.hasText(value)) {
            try {
                return UUID.fromString(value.trim());
            } catch (IllegalArgumentException ignored) {
                // Generate a trusted identifier rather than reflecting arbitrary client input.
            }
        }
        return UUID.randomUUID();
    }

    private static String safeCorrelationId(String value) {
        return value != null && SAFE_CORRELATION_ID.matcher(value).matches() ? value : null;
    }

    private static String safeTraceparent(String value) {
        return value != null && TRACEPARENT.matcher(value).matches() ? value : null;
    }
}
