package com.pesaguard.backend.security.sessions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.pesaguard.backend.onboarding.api.DeveloperOnboardingStatus;
import com.pesaguard.backend.onboarding.application.DeveloperOnboardingStatusService;
import com.pesaguard.backend.security.principals.AuthenticatedUser;

class DeveloperWorkspaceAccessFilterTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void allowsVerifiedDeveloperIntoDashboardBeforeOptionalSetupIsComplete() throws Exception {
        DeveloperOnboardingStatusService statusService = mock(DeveloperOnboardingStatusService.class);
        AuthenticatedUser developer = new AuthenticatedUser(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "developer@example.com", "Developer", Set.of("ROLE_OWNER"));
        when(statusService.statusFor(developer)).thenReturn(new DeveloperOnboardingStatus(
                true, true, true, false, false, "project", false, true, false, false,
                "welcome", List.of(), List.of(), "Developer Organization", "", null, null, null, null));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(developer, null, List.of()));

        DeveloperWorkspaceAccessFilter filter = new DeveloperWorkspaceAccessFilter(
                statusService, mock(RestAccessDeniedHandler.class));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/projects");
        request.setServletPath("/api/v1/projects");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilterInternal(request, new MockHttpServletResponse(), chain);

        verify(statusService).statusFor(developer);
        assertThat(chain.getRequest()).isNotNull();
    }
}
