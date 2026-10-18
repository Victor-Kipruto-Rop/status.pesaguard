package com.pesaguard.backend.security.sessions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.pesaguard.backend.organization.domain.OrganizationStatus;
import com.pesaguard.backend.security.principals.AuthenticatedUser;
import com.pesaguard.backend.security.tokens.AccessTokenService;
import com.pesaguard.backend.security.tokens.RevokedTokenRegistry;
import com.pesaguard.backend.serviceaccount.application.ServiceAccountService;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

import jakarta.servlet.http.HttpServletRequest;

class SessionAuthenticationFilterTest {

    @Test
    void letsOnlyTheFixedSandboxKeyEndpointHandleItsOwnBearerCredential() {
        SessionAuthenticationFilter filter = new SessionAuthenticationFilter(
                mock(AccessTokenService.class),
                mock(RevokedTokenRegistry.class),
                mock(RestAuthenticationEntryPoint.class),
                mock(RestAccessDeniedHandler.class),
                mock(ServiceAccountService.class),
                mock(SessionActivityRecorder.class));
        HttpServletRequest request = mock(HttpServletRequest.class);

        when(request.getMethod()).thenReturn("GET");
        when(request.getServletPath()).thenReturn("/api/v1/sandbox/transactions");
        assertThat(filter.shouldNotFilter(request)).isTrue();

        when(request.getMethod()).thenReturn("POST");
        assertThat(filter.shouldNotFilter(request)).isFalse();

        when(request.getMethod()).thenReturn("GET");
        when(request.getServletPath()).thenReturn("/api/v1/auth/session");
        assertThat(filter.shouldNotFilter(request)).isFalse();

        when(request.getServletPath()).thenReturn("/api/v1/key-data/usage");
        assertThat(filter.shouldNotFilter(request)).isTrue();
        when(request.getMethod()).thenReturn("POST");
        assertThat(filter.shouldNotFilter(request)).isFalse();

        when(request.getServletPath()).thenReturn("/api/v1/key-data-evil/usage");
        when(request.getMethod()).thenReturn("GET");
        assertThat(filter.shouldNotFilter(request)).isFalse();

        when(request.getMethod()).thenReturn("POST");
        when(request.getServletPath()).thenReturn("/api/v1/service-accounts/token");
        assertThat(filter.shouldNotFilter(request)).isTrue();
    }

    @Test
    void enrollmentChallengeCannotReachOrdinaryAuthenticatedRoutes() throws Exception {
        AccessTokenService accessTokens = mock(AccessTokenService.class);
        RevokedTokenRegistry revokedTokens = mock(RevokedTokenRegistry.class);
        RestAccessDeniedHandler deniedHandler = mock(RestAccessDeniedHandler.class);
        SessionAuthenticationFilter filter = new SessionAuthenticationFilter(
                accessTokens,
                revokedTokens,
                mock(RestAuthenticationEntryPoint.class),
                deniedHandler,
                mock(ServiceAccountService.class),
                mock(SessionActivityRecorder.class));
        UUID sessionId = UUID.randomUUID();
        AuthenticatedUser challenge = new AuthenticatedUser(
                UUID.randomUUID(), UUID.randomUUID(), sessionId, "developer@example.com", "",
                Set.of("ROLE_DEVELOPER"), OrganizationStatus.ACTIVE, false, Set.of(), true);
        when(accessTokens.verify("restricted-token")).thenReturn(Optional.of(challenge));
        when(revokedTokens.isRevoked(sessionId)).thenReturn(false);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/auth/session");
        request.setServletPath("/api/v1/auth/session");
        request.addHeader("Authorization", "Bearer restricted-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilterInternal(request, response, chain);

        verify(deniedHandler).handle(eq(request), eq(response), any(
                org.springframework.security.access.AccessDeniedException.class));
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void recordsActivityForAnAuthenticatedSession() throws Exception {
        AccessTokenService accessTokens = mock(AccessTokenService.class);
        RevokedTokenRegistry revokedTokens = mock(RevokedTokenRegistry.class);
        SessionActivityRecorder activityRecorder = mock(SessionActivityRecorder.class);
        SessionAuthenticationFilter filter = new SessionAuthenticationFilter(
                accessTokens,
                revokedTokens,
                mock(RestAuthenticationEntryPoint.class),
                mock(RestAccessDeniedHandler.class),
                mock(ServiceAccountService.class),
                activityRecorder);
        AuthenticatedUser user = new AuthenticatedUser(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "developer@example.com", "Developer", Set.of("ROLE_DEVELOPER"),
                OrganizationStatus.ACTIVE);
        when(accessTokens.verify("valid-token")).thenReturn(Optional.of(user));
        when(revokedTokens.isRevoked(user.sessionId())).thenReturn(false);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/auth/session");
        request.setServletPath("/api/v1/auth/session");
        request.addHeader("Authorization", "Bearer valid-token");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilterInternal(request, new MockHttpServletResponse(), chain);

        verify(activityRecorder).record(user);
        assertThat(chain.getRequest()).isNotNull();
    }
}
