package com.pesaguard.backend.serviceaccount.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.pesaguard.backend.audit.application.AuditService;
import com.pesaguard.backend.common.exception.BusinessException;
import com.pesaguard.backend.config.OAuthProperties;
import com.pesaguard.backend.oauth.application.OAuthTokenService;
import com.pesaguard.backend.organization.infrastructure.OrganizationMembershipRepository;
import com.pesaguard.backend.organization.infrastructure.OrganizationRepository;
import com.pesaguard.backend.rbac.application.AuthorizationService;
import com.pesaguard.backend.rbac.domain.Permission;
import com.pesaguard.backend.security.credentials.CredentialCryptoService;
import com.pesaguard.backend.security.principals.AuthenticatedUser;
import com.pesaguard.backend.serviceaccount.api.ServiceAccountRequest;
import com.pesaguard.backend.serviceaccount.api.ServiceAccountTokenRequest;
import com.pesaguard.backend.serviceaccount.domain.ServiceAccount;
import com.pesaguard.backend.serviceaccount.domain.ServiceAccountAccessToken;
import com.pesaguard.backend.serviceaccount.infrastructure.ServiceAccountAccessTokenRepository;
import com.pesaguard.backend.serviceaccount.infrastructure.ServiceAccountRepository;

@ExtendWith(MockitoExtension.class)
class ServiceAccountServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-05T09:00:00Z");
    private final UUID userId = UUID.randomUUID();
    private final UUID organizationId = UUID.randomUUID();
    private final AuthenticatedUser principal = new AuthenticatedUser(
            userId, organizationId, UUID.randomUUID(), "developer@example.com", "Developer", Set.of());

    @Mock private ServiceAccountRepository accountRepository;
    @Mock private ServiceAccountAccessTokenRepository tokenRepository;
    @Mock private AuthorizationService authorizationService;
    @Mock private CredentialCryptoService crypto;
    @Mock private AuditService auditService;
    @Mock private OAuthTokenService oauthTokenService;
    @Mock private OrganizationMembershipRepository membershipRepository;
    @Mock private OrganizationRepository organizationRepository;

    private ServiceAccountService service;

    @BeforeEach
    void setUp() {
        OAuthProperties oauthProperties = new OAuthProperties(
                Duration.ofMinutes(5), Duration.ofMinutes(10), Duration.ofDays(30),
                Duration.ofMinutes(10), Duration.ofMinutes(1), 10);
        service = new ServiceAccountService(accountRepository, tokenRepository, authorizationService,
                crypto, auditService, oauthTokenService, membershipRepository, organizationRepository,
                oauthProperties, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void createsOrganizationScopedAccountAndReturnsSecretOnce() {
        when(authorizationService.effectivePermissions(principal))
                .thenReturn(Set.of(Permission.CREDENTIAL_CREATE, Permission.PROJECT_READ));
        when(crypto.randomToken(36)).thenReturn("secret-material");
        when(crypto.randomToken(18)).thenReturn("client-material");
        when(crypto.hmacSha256(any(String.class))).thenReturn("stored-hash");
        when(accountRepository.saveAndFlush(any(ServiceAccount.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var created = service.create(principal,
                new ServiceAccountRequest("Worker", "Background worker", Set.of("project:read")));

        assertThat(created.serviceAccount().clientId()).startsWith("pgsa_");
        assertThat(created.clientSecret()).startsWith("pgss_");
        assertThat(created.serviceAccount().scopes()).containsExactly("project:read");
        ArgumentCaptor<ServiceAccount> accountCaptor = ArgumentCaptor.forClass(ServiceAccount.class);
        verify(accountRepository).saveAndFlush(accountCaptor.capture());
        assertThat(accountCaptor.getValue().getClientSecretHash()).isEqualTo("stored-hash");
    }

    @Test
    void rejectsScopesCreatorDoesNotHold() {
        when(authorizationService.effectivePermissions(principal)).thenReturn(Set.of(Permission.PROJECT_READ));

        assertThatThrownBy(() -> service.create(principal,
                new ServiceAccountRequest("Worker", null, Set.of("project:delete"))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("creator does not hold");
    }

    @Test
    void clientCredentialsGrantIssuesStoredShortLivedBearerToken() {
        ServiceAccount account = ServiceAccount.create(organizationId, "Worker", null,
                "pgsa_worker", "expected-hash", "pgss_hint", Set.of("project:read"), userId);
        when(accountRepository.findByClientId("pgsa_worker")).thenReturn(Optional.of(account));
        when(crypto.hmacSha256("secret")).thenReturn("expected-hash");
        when(crypto.randomToken(32)).thenReturn("access-token-material");
        when(crypto.hmacSha256("pgsat_access-token-material")).thenReturn("access-token-hash");
        when(tokenRepository.saveAndFlush(any(ServiceAccountAccessToken.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var issued = service.issueAccessToken(
                new ServiceAccountTokenRequest("client_credentials", "pgsa_worker", "secret"), "127.0.0.1");

        assertThat(issued.accessToken()).isEqualTo("pgsat_access-token-material");
        assertThat(issued.expiresIn()).isEqualTo(600);
        assertThat(issued.scopes()).containsExactly("project:read");
        ArgumentCaptor<ServiceAccountAccessToken> tokenCaptor =
                ArgumentCaptor.forClass(ServiceAccountAccessToken.class);
        verify(tokenRepository).saveAndFlush(tokenCaptor.capture());
        assertThat(tokenCaptor.getValue().getTokenHash()).isEqualTo("access-token-hash");
        assertThat(tokenCaptor.getValue().getExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(10)));
        verify(oauthTokenService).enforceClientThrottle("127.0.0.1");
    }
}
