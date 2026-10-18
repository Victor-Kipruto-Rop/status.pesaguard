package com.pesaguard.backend.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.pesaguard.backend.security.principals.AuthenticatedUser;

class TenantContextFilterTest {

    private final TenantContextFilter filter = new TenantContextFilter();

    @AfterEach
    void clearContext() {
        TenantContextHolder.clear();
        SecurityContextHolder.clearContext();
        org.slf4j.MDC.clear();
    }

    @Test
    void doesNotInferProjectOrEnvironmentIdsFromTheRawRequestUri() throws Exception {
        UUID organizationId = UUID.randomUUID();
        AuthenticatedUser user = new AuthenticatedUser(
                UUID.randomUUID(), organizationId, UUID.randomUUID(), "user@example.test", "User", Set.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, "ignored", List.of()));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/projects/00000000-0000-0000-0000-000000000002"
                + "/environments/00000000-0000-0000-0000-000000000003/credentials");

        filter.doFilter(request, new MockHttpServletResponse(), (servletRequest, servletResponse) -> {
            assertThat(TenantContextHolder.require().projectId()).isNull();
            assertThat(TenantContextHolder.require().environmentId()).isNull();
        });
    }
}
