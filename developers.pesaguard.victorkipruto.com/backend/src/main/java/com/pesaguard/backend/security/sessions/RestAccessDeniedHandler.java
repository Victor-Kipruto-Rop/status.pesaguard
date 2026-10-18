package com.pesaguard.backend.security.sessions;

import java.io.IOException;
import java.time.Instant;

import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import com.pesaguard.backend.common.api.RequestContext;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException accessDeniedException) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write(
                "{\"error\":{\"code\":\"FORBIDDEN\",\"message\":\"You do not have permission to perform this action.\","
                        + "\"requestId\":\"" + RequestContext.requestId(request) + "\",\"timestamp\":\"" + Instant.now() + "\","
                        + "\"violations\":[]}}");
    }
}
