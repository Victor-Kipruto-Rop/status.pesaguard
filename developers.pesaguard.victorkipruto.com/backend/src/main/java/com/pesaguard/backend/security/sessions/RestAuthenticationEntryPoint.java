package com.pesaguard.backend.security.sessions;

import java.io.IOException;
import java.time.Instant;

import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import com.pesaguard.backend.common.api.RequestContext;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authenticationException) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Cache-Control", "no-store");
        var requestId = RequestContext.requestId(request);
        response.getWriter().write(
                "{\"error\":{\"code\":\"UNAUTHENTICATED\",\"message\":\"Authentication is required.\","
                        + "\"requestId\":\"" + requestId + "\",\"timestamp\":\"" + Instant.now() + "\","
                        + "\"violations\":[]}}");
    }
}
