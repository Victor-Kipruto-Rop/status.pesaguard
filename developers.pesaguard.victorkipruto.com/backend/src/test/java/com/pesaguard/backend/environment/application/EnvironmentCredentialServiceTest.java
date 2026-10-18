package com.pesaguard.backend.environment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.pesaguard.backend.common.exception.BusinessException;
import com.pesaguard.backend.environment.domain.EnvironmentCredential;
import com.pesaguard.backend.environment.domain.EnvironmentCredentialType;
import com.pesaguard.backend.environment.domain.EnvironmentType;
import com.pesaguard.backend.environment.domain.ProjectEnvironment;
import com.pesaguard.backend.environment.infrastructure.EnvironmentCredentialRepository;
import com.pesaguard.backend.environment.infrastructure.ProjectEnvironmentRepository;
import com.pesaguard.backend.environment.infrastructure.EnvironmentLimitsRepository;
import com.pesaguard.backend.security.credentials.SecretEncryptionService;

/**
 * Tier isolation of credentials.
 *
 * <p>The control under test is "production credentials cannot be reused in
 * sandbox". A sandbox environment is reachable by everyone with sandbox access
 * and by whatever third party that sandbox talks to, so a production secret
 * registered there is a production compromise waiting to happen.
 */
class EnvironmentCredentialServiceTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private ProjectEnvironmentRepository environmentRepository;
    private EnvironmentCredentialRepository credentialRepository;
    private EnvironmentCredentialService service;

    private UUID organizationId;
    private UUID projectId;
    private UUID environmentId;

    @BeforeEach
    void setUp() {
        environmentRepository = mock(ProjectEnvironmentRepository.class);
        credentialRepository = mock(EnvironmentCredentialRepository.class);
        SecretKeySpec key = new SecretKeySpec("01234567890123456789012345678901".getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        Clock clock = Clock.fixed(T0, ZoneOffset.UTC);
        SecretEncryptionService encryptionService = mock(SecretEncryptionService.class);
        when(encryptionService.encrypt(anyString())).thenReturn("encrypted-secret");
        EnvironmentLimitsRepository limitsRepository = mock(EnvironmentLimitsRepository.class);
        when(limitsRepository.findByEnvironmentId(any())).thenReturn(Optional.empty());
        service = new EnvironmentCredentialService(
                environmentRepository, credentialRepository, key, null, null, null, encryptionService,
                limitsRepository, mock(EnvironmentAccessPolicyService.class), clock);

        organizationId = UUID.randomUUID();
        projectId = UUID.randomUUID();
        environmentId = UUID.randomUUID();
    }

    private void givenEnvironment() {
        when(environmentRepository.findByIdAndOrganizationIdAndProjectId(
                environmentId, organizationId, projectId))
                .thenReturn(Optional.of(ProjectEnvironment.create(
                        organizationId, projectId, "sandbox",
                        EnvironmentType.SANDBOX, UUID.randomUUID(), T0)));
    }

    private void givenNoExistingVersions() {
        when(credentialRepository.findByEnvironmentIdAndNameOrderByVersionDesc(
                any(), anyString())).thenReturn(List.of());
    }

    @Test
    void aSecretAlreadyUsedInAnotherEnvironmentIsRejected() {
        givenEnvironment();
        // The same secret is already stored under a different environment in this
        // project, which is exactly the production-into-sandbox case.
        when(credentialRepository.existsByFingerprintAndProjectIdAndEnvironmentIdNot(
                anyString(), eq(projectId), eq(environmentId))).thenReturn(true);
        givenNoExistingVersions();

        assertThatThrownBy(() -> service.store(organizationId, projectId, environmentId,
                "api-key", EnvironmentCredentialType.API_KEY, "prod-secret-value"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already registered in another environment");

        verify(credentialRepository, never()).saveAndFlush(any());
    }

    @Test
    void theRejectionDoesNotRevealWhichEnvironmentHoldsTheSecret() {
        givenEnvironment();
        when(credentialRepository.existsByFingerprintAndProjectIdAndEnvironmentIdNot(
                anyString(), eq(projectId), eq(environmentId))).thenReturn(true);
        givenNoExistingVersions();

        assertThatThrownBy(() -> service.store(organizationId, projectId, environmentId,
                "api-key", EnvironmentCredentialType.API_KEY, "prod-secret-value"))
                // Naming the tier holding it tells an attacker where to look next,
                // and they may not be entitled to know.
                .hasMessageNotContaining("PRODUCTION")
                .hasMessageNotContaining("production");
    }

    @Test
    void aSecretUniqueToThisEnvironmentIsAccepted() {
        givenEnvironment();
        when(credentialRepository.existsByFingerprintAndProjectIdAndEnvironmentIdNot(
                anyString(), eq(projectId), eq(environmentId))).thenReturn(false);
        givenNoExistingVersions();
        when(credentialRepository.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));

        service.store(organizationId, projectId, environmentId,
                "api-key", EnvironmentCredentialType.API_KEY, "sandbox-only-secret");

        verify(credentialRepository).saveAndFlush(any(EnvironmentCredential.class));
    }

    @Test
    void theReuseCheckIsScopedToTheProject() {
        givenEnvironment();

        service.store(organizationId, projectId, environmentId,
                "api-key", EnvironmentCredentialType.API_KEY, "a-secret");

        // Scoped, not global: the same secret in a different project is a
        // different customer and must not collide.
        ArgumentCaptor<UUID> project = ArgumentCaptor.forClass(UUID.class);
        verify(credentialRepository).existsByFingerprintAndProjectIdAndEnvironmentIdNot(
                anyString(), project.capture(), eq(environmentId));
        assertThat(project.getValue()).isEqualTo(projectId);
    }

    @Test
    void aBlankSecretIsRejected() {
        givenEnvironment();

        assertThatThrownBy(() -> service.store(organizationId, projectId, environmentId,
                "api-key", EnvironmentCredentialType.API_KEY, "   "))
                .isInstanceOf(BusinessException.class);

        verify(credentialRepository, never()).saveAndFlush(any());
    }

    @Test
    void anEnvironmentOutsideTheProjectCannotReceiveCredentials() {
        when(environmentRepository.findByIdAndOrganizationIdAndProjectId(
                any(), any(), any())).thenReturn(Optional.empty());

        // A borrowed project identifier must not be able to attach a credential to
        // an environment belonging to another tenant.
        assertThatThrownBy(() -> service.store(organizationId, projectId, environmentId,
                "api-key", EnvironmentCredentialType.API_KEY, "a-secret"))
                .isInstanceOf(BusinessException.class);

        verify(credentialRepository, never()).saveAndFlush(any());
    }

    @Test
    void revokingAnUnknownCredentialReportsNoChange() {
        when(credentialRepository.findByIdAndEnvironmentIdAndProjectIdAndOrganizationId(
                any(), any(), any(), any())).thenReturn(Optional.empty());

        // Reports "nothing changed" rather than failing: the caller asked for a
        // state that is already true, and a 404 here would leak whether the
        // credential exists in another tenant.
        assertThat(service.revoke(
                organizationId, projectId, environmentId, UUID.randomUUID())).isFalse();
    }
}
