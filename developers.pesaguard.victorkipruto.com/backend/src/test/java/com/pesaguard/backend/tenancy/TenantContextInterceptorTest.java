package com.pesaguard.backend.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

class TenantContextInterceptorTest {

    private final TenantContextInterceptor interceptor = new TenantContextInterceptor();

    @AfterEach
    void clearContext() {
        TenantContextHolder.clear();
        org.slf4j.MDC.clear();
    }

    @Test
    void usesMappedRouteVariablesAndRestoresTheFilterContext() throws Exception {
        UUID organizationId = UUID.randomUUID();
        TenantContext base = new TenantContext(
                organizationId, organizationId, UUID.randomUUID(), UUID.randomUUID(), null, null,
                "request-id", null, null);
        UUID projectId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID environmentId = UUID.fromString("00000000-0000-0000-0000-000000000003");
        TenantContextHolder.set(base);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of(
                "projectId", projectId.toString(),
                "environmentId", environmentId.toString()));

        assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), new Object())).isTrue();
        assertThat(TenantContextHolder.require().projectId()).isEqualTo(projectId);
        assertThat(TenantContextHolder.require().environmentId()).isEqualTo(environmentId);

        interceptor.afterCompletion(request, new MockHttpServletResponse(), new Object(), null);
        assertThat(TenantContextHolder.require()).isEqualTo(base);
    }

    @Test
    void ignoresMissingOrNonCanonicalRouteValuesInsteadOfGuessingThem() throws Exception {
        UUID organizationId = UUID.randomUUID();
        TenantContextHolder.set(new TenantContext(
                organizationId, organizationId, UUID.randomUUID(), UUID.randomUUID(), null, null,
                "request-id", null, null));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of(
                "projectId", "00000000-0000-0000-0000-000000000002",
                "environmentId", "00000000-0000-0000-0000-00000000000A"));

        interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        assertThat(TenantContextHolder.require().projectId())
                .isEqualTo(UUID.fromString("00000000-0000-0000-0000-000000000002"));
        assertThat(TenantContextHolder.require().environmentId()).isNull();
        interceptor.afterCompletion(request, new MockHttpServletResponse(), new Object(), null);
    }
}
