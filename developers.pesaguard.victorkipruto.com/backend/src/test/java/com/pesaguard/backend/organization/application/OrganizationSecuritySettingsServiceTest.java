package com.pesaguard.backend.organization.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.pesaguard.backend.audit.application.AuditService;
import com.pesaguard.backend.common.exception.BusinessException;
import com.pesaguard.backend.organization.api.UpdateSecuritySettingsRequest;
import com.pesaguard.backend.organization.domain.Organization;
import com.pesaguard.backend.organization.domain.OrganizationSecuritySettings;
import com.pesaguard.backend.security.principals.AuthenticatedUser;
import com.pesaguard.backend.organization.infrastructure.OrganizationRepository;
import com.pesaguard.backend.organization.infrastructure.OrganizationSecuritySettingsRepository;
import com.pesaguard.backend.security.authentication.IpRangeMatcher;
import com.pesaguard.backend.tenancy.TenantAwareCache;
import com.pesaguard.backend.tenancy.TenantEventPublisher;

class OrganizationSecuritySettingsServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private final OrganizationSecuritySettingsRepository settingsRepository =
            mock(OrganizationSecuritySettingsRepository.class);
    private final OrganizationRepository organizationRepository = mock(OrganizationRepository.class);
    private final OrganizationSecuritySettingsService service = new OrganizationSecuritySettingsService(
            settingsRepository,
            organizationRepository,
            mock(OrganizationAuthorization.class),
            new IpRangeMatcher(),
            mock(AuditService.class),
            mock(TenantEventPublisher.class),
            new TenantAwareCache(),
            Clock.fixed(NOW, ZoneOffset.UTC));

    private final UUID organizationId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    private AuthenticatedUser principal() {
        return new AuthenticatedUser(
                userId, organizationId, UUID.randomUUID(), "owner@example.com", "Owner", Set.of("ROLE_OWNER"));
    }

    private void givenExistingOrganization() {
        when(organizationRepository.findByIdForUpdate(organizationId)).thenReturn(Optional.of(
                Organization.create("Acme", "acme-" + organizationId, userId, NOW)));
    }

    @BeforeEach
    void setUp() {
        givenExistingOrganization();
    }

    @Test
    void passwordLoginIsAllowedWithDefaultSettings() {
        assertThatCode(() -> service.assertPasswordLoginAllowed(settings()))
                .doesNotThrowAnyException();
    }

    @Test
    void mfaRequirementIsAcceptedForTheNextSignIn() {
        OrganizationSecuritySettings settings = settings();
        settings.update("PASSWORD", 480, 120, 10, 12, 72, true, "", "LOGIN_FAILURE", null, NOW);

        assertThatCode(() -> service.assertPasswordLoginAllowed(settings)).doesNotThrowAnyException();
        assertThat(service.requiresMfa(settings, com.pesaguard.backend.organization.domain.OrganizationRole.DEVELOPER))
                .isTrue();
    }

    @Test
    void disablingPasswordAuthenticationBlocksLogin() {
        OrganizationSecuritySettings settings = settings();
        settings.update("OIDC", 480, 120, 10, 12, 72, false, "", "LOGIN_FAILURE", null, NOW);

        assertThatThrownBy(() -> service.assertPasswordLoginAllowed(settings))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Password authentication is disabled");
    }

    @Test
    void ipAllowlistBlocksUntrustedNetworks() {
        OrganizationSecuritySettings settings = settings();
        settings.update("PASSWORD", 480, 120, 10, 12, 72, false, "203.0.113.0/24", "LOGIN_FAILURE", null, NOW);

        assertThatCode(() -> service.assertIpAllowed(settings, "203.0.113.9")).doesNotThrowAnyException();
        assertThatThrownBy(() -> service.assertIpAllowed(settings, "198.51.100.9"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not permitted");
    }

    @Test
    void updateRejectsUnsupportedAuthenticationProviders() {
        UpdateSecuritySettingsRequest request = new UpdateSecuritySettingsRequest(
                Set.of("OIDC"), 480, 120, 10, 12, 72, false, Set.of(), Set.of("LOGIN_FAILURE"));

        assertThat(catchBusiness(() -> service.update(principal(), request)))
                .matches(exception -> exception.status() == HttpStatus.BAD_REQUEST
                        && "UNSUPPORTED_AUTH_METHOD".equals(exception.code()));
    }

    @Test
    void updateAllowsEnablingMfa() {
        UpdateSecuritySettingsRequest request = new UpdateSecuritySettingsRequest(
                Set.of("PASSWORD"), 480, 120, 10, 12, 72, true, Set.of(), Set.of("LOGIN_FAILURE"));
        when(settingsRepository.findById(organizationId)).thenReturn(Optional.of(settings()));

        assertThatCode(() -> service.update(principal(), request)).doesNotThrowAnyException();
    }

    @Test
    void adminOnlyMfaPolicyDoesNotRequireMfaFromDevelopers() {
        OrganizationSecuritySettings settings = settings();
        settings.update("PASSWORD", 480, 120, 10, 12, 72, false, true,
                "", "LOGIN_FAILURE", null, NOW);

        assertThat(service.requiresMfa(settings,
                com.pesaguard.backend.organization.domain.OrganizationRole.ADMIN)).isTrue();
        assertThat(service.requiresMfa(settings,
                com.pesaguard.backend.organization.domain.OrganizationRole.DEVELOPER)).isFalse();
    }

    @Test
    void olderSecuritySettingsUpdatesPreserveAdminMfaPolicy() {
        OrganizationSecuritySettings existing = settings();
        existing.update("PASSWORD", 480, 120, 10, 12, 72, false, true,
                "", "LOGIN_FAILURE", null, NOW);
        when(settingsRepository.findById(organizationId)).thenReturn(Optional.of(existing));
        UpdateSecuritySettingsRequest request = new UpdateSecuritySettingsRequest(
                Set.of("PASSWORD"), 480, 120, 10, 12, 72, false, null,
                Set.of(), Set.of("LOGIN_FAILURE"));

        assertThat(service.update(principal(), request).mfaRequiredForAdmins()).isTrue();
    }

    @Test
    void updateRejectsUnsupportedSecurityEventTypes() {
        UpdateSecuritySettingsRequest request = new UpdateSecuritySettingsRequest(
                Set.of("PASSWORD"), 480, 120, 10, 12, 72, false, Set.of(), Set.of("NOT_A_REAL_EVENT"));

        assertThat(catchBusiness(() -> service.update(principal(), request)).code())
                .isEqualTo("UNSUPPORTED_SECURITY_EVENT");
    }

    @Test
    void updateRejectsInvertedCredentialPolicy() {
        UpdateSecuritySettingsRequest request = new UpdateSecuritySettingsRequest(
                Set.of("PASSWORD"), 480, 120, 10, 40, 20, false, Set.of(), Set.of("LOGIN_FAILURE"));

        assertThat(catchBusiness(() -> service.update(principal(), request)).code())
                .isEqualTo("INVALID_CREDENTIAL_POLICY");
    }

    @Test
    void updateRejectsInvalidCidrRanges() {
        UpdateSecuritySettingsRequest request = new UpdateSecuritySettingsRequest(
                Set.of("PASSWORD"), 480, 120, 10, 12, 72, false, Set.of("nonsense"), Set.of("LOGIN_FAILURE"));

        assertThat(catchBusiness(() -> service.update(principal(), request)).code())
                .isEqualTo("INVALID_IP_RESTRICTION");
    }

    private OrganizationSecuritySettings settings() {
        return OrganizationSecuritySettings.defaults(organizationId, NOW);
    }

    private BusinessException catchBusiness(Runnable action) {
        try {
            action.run();
        } catch (BusinessException exception) {
            return exception;
        }
        throw new AssertionError("Expected a BusinessException");
    }
}