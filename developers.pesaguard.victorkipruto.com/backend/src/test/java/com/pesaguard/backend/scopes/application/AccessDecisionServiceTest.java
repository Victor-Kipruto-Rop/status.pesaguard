package com.pesaguard.backend.scopes.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.pesaguard.backend.credentials.api.ApiKey;
import com.pesaguard.backend.credentials.api.ApiKeyRepository;
import com.pesaguard.backend.credentials.api.ApiKeyStatus;
import com.pesaguard.backend.organization.domain.OrganizationStatus;
import com.pesaguard.backend.scopes.domain.AccessDecision;
import com.pesaguard.backend.scopes.domain.ApiAccessDecisionRecord;
import com.pesaguard.backend.scopes.domain.ApiScopeAssignment;
import com.pesaguard.backend.scopes.domain.ApiScopeDefinition;
import com.pesaguard.backend.scopes.infrastructure.ApiAccessDecisionRepository;
import com.pesaguard.backend.scopes.infrastructure.ApiScopeAssignmentRepository;
import com.pesaguard.backend.scopes.infrastructure.ApiScopeDefinitionRepository;
import com.pesaguard.backend.security.principals.AuthenticatedUser;

/**
 * The access decision is the security boundary for API calls, so these tests
 * concentrate on the ways it could wrongly allow: a missing factor resolving
 * permissively, a credential from another organization being accepted, and a
 * revoked credential passing.
 */
class AccessDecisionServiceTest {

    private static final Instant NOW = Instant.parse("2026-03-01T00:00:00Z");

    private final ApiScopeDefinitionRepository definitionRepository = mock(ApiScopeDefinitionRepository.class);
    private final ApiScopeAssignmentRepository assignmentRepository = mock(ApiScopeAssignmentRepository.class);
    private final ApiAccessDecisionRepository decisionRepository = mock(ApiAccessDecisionRepository.class);
    private final ApiKeyRepository apiKeyRepository = mock(ApiKeyRepository.class);

    private final UUID organizationId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID apiKeyId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID environmentId = UUID.randomUUID();

    private AccessDecisionService service;

    private AuthenticatedUser activePrincipal() {
        return new AuthenticatedUser(userId, organizationId, UUID.randomUUID(),
                "dev@example.com", "Dev", Set.of("ROLE_DEVELOPER"), OrganizationStatus.ACTIVE);
    }

    private ApiKey credential(ApiKeyStatus status, UUID project, UUID environment) {
        ApiKey key = ApiKey.create(organizationId, project, environment, "k",
                "pgk_1", "hash", "payments:read", NOW.plusSeconds(3600), userId);
        key.activate();
        if (status != ApiKeyStatus.ACTIVE) {
            key.suspend(NOW);
        }
        return key;
    }

    @BeforeEach
    void setUp() {
        service = new AccessDecisionService(definitionRepository, assignmentRepository,
                decisionRepository, apiKeyRepository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void givenScopeRegistered(String name, boolean restricted, boolean deprecated) {
        ApiScopeDefinition definition = mock(ApiScopeDefinition.class);
        when(definition.getName()).thenReturn(name);
        when(definition.isRestricted()).thenReturn(restricted);
        when(definition.isDeprecated()).thenReturn(deprecated);
        when(definitionRepository.findByName(name)).thenReturn(Optional.of(definition));
    }

    private void givenScopeGranted(String name, boolean active) {
        ApiScopeAssignment assignment = mock(ApiScopeAssignment.class);
        when(assignment.isActive()).thenReturn(active);
        when(assignmentRepository.findByApiKeyIdAndScopeName(apiKeyId, name))
                .thenReturn(Optional.of(assignment));
    }

    private void givenCredential(ApiKey key) {
        when(apiKeyRepository.findByIdAndOrganizationId(apiKeyId, organizationId))
                .thenReturn(Optional.of(key));
    }

    private AccessDecision decide(AuthenticatedUser principal, String scope) {
        return service.decide(principal, apiKeyId, projectId, environmentId, scope,
                "req-1", "203.0.113.5");
    }
@Test
    void anUnauthenticatedCallerIsDenied() {
        AccessDecision decision = service.decide(null, apiKeyId, projectId, environmentId,
                "payments:read", "req-1", "203.0.113.5");

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reasonCode())
                .isEqualTo(AccessDecision.ReasonCode.IDENTITY_UNAUTHENTICATED);
    }

    @Test
    void anInactiveOrganizationIsDenied() {
        AuthenticatedUser suspended = new AuthenticatedUser(userId, organizationId, UUID.randomUUID(),
                "dev@example.com", "Dev", Set.of("ROLE_DEVELOPER"), OrganizationStatus.SUSPENDED);

        AccessDecision decision = decide(suspended, "payments:read");

        assertThat(decision.reasonCode())
                .isEqualTo(AccessDecision.ReasonCode.ORGANIZATION_UNAVAILABLE);
    }

    @Test
    void aMissingProjectIsDenied() {
        AccessDecision decision = service.decide(activePrincipal(), apiKeyId, null, environmentId,
                "payments:read", "req-1", "203.0.113.5");

        assertThat(decision.reasonCode())
                .isEqualTo(AccessDecision.ReasonCode.PROJECT_UNAVAILABLE);
    }

    @Test
    void aCredentialFromAnotherOrganizationIsDenied() {
        // The repository is queried by (id, organization); an empty result means the
        // credential is not this organization's and must not be evaluated further.
        when(apiKeyRepository.findByIdAndOrganizationId(apiKeyId, organizationId))
                .thenReturn(Optional.empty());

        AccessDecision decision = decide(activePrincipal(), "payments:read");

        assertThat(decision.reasonCode())
                .isEqualTo(AccessDecision.ReasonCode.CREDENTIAL_UNAVAILABLE);
    }

    @Test
    void aCredentialBoundToAnotherEnvironmentIsDenied() {
        givenCredential(credential(ApiKeyStatus.ACTIVE, projectId, UUID.randomUUID()));
        givenScopeRegistered("payments:read", false, false);

        AccessDecision decision = decide(activePrincipal(), "payments:read");

        assertThat(decision.reasonCode())
                .isEqualTo(AccessDecision.ReasonCode.CREDENTIAL_CONTEXT_MISMATCH);
    }

    @Test
    void anUnknownScopeIsDenied() {
        givenCredential(credential(ApiKeyStatus.ACTIVE, projectId, environmentId));

        AccessDecision decision = decide(activePrincipal(), "payments:teleport");

        assertThat(decision.reasonCode())
                .isEqualTo(AccessDecision.ReasonCode.SCOPE_UNKNOWN);
    }

    @Test
    void aRegisteredButUnassignedScopeIsDenied() {
        givenCredential(credential(ApiKeyStatus.ACTIVE, projectId, environmentId));
        givenScopeRegistered("payments:read", false, false);
        when(assignmentRepository.findByApiKeyIdAndScopeName(apiKeyId, "payments:read"))
                .thenReturn(Optional.empty());

        AccessDecision decision = decide(activePrincipal(), "payments:read");

        assertThat(decision.reasonCode())
                .isEqualTo(AccessDecision.ReasonCode.SCOPE_NOT_ASSIGNED);
    }

    @Test
    void aRevokedAssignmentIsDenied() {
        givenCredential(credential(ApiKeyStatus.ACTIVE, projectId, environmentId));
        givenScopeRegistered("payments:read", false, false);
        givenScopeGranted("payments:read", false);

        AccessDecision decision = decide(activePrincipal(), "payments:read");

        assertThat(decision.reasonCode())
                .isEqualTo(AccessDecision.ReasonCode.SCOPE_NOT_ASSIGNED);
    }

@Test
    void aFullyValidRequestIsAllowed() {
        givenCredential(credential(ApiKeyStatus.ACTIVE, projectId, environmentId));
        givenScopeRegistered("payments:read", false, false);
        givenScopeGranted("payments:read", true);

        AccessDecision decision = decide(activePrincipal(), "payments:read");

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.reasonCode()).isEqualTo(AccessDecision.ReasonCode.ALLOWED);
    }

    @Test
    void aDeprecatedScopeStillWorksButIsReported() {
        // Refusing would break live integrations. The trace records it so the
        // caller can surface the deprecation and the client can migrate.
        givenCredential(credential(ApiKeyStatus.ACTIVE, projectId, environmentId));
        givenScopeRegistered("payments:read", false, true);
        givenScopeGranted("payments:read", true);

        AccessDecision decision = decide(activePrincipal(), "payments:read");

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.factorTrace()).contains("SCOPE_DEPRECATED=PASS");
    }

    @Test
    void aSuspendedCredentialIsDenied() {
        givenCredential(credential(ApiKeyStatus.SUSPENDED, projectId, environmentId));
        givenScopeRegistered("payments:read", false, false);
        givenScopeGranted("payments:read", true);

        AccessDecision decision = decide(activePrincipal(), "payments:read");

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reasonCode())
                .isEqualTo(AccessDecision.ReasonCode.CREDENTIAL_STATUS_BLOCKED);
    }

    @Test
    void anExpiredCredentialIsDenied() {
        ApiKey key = ApiKey.create(organizationId, projectId, environmentId, "k",
                "pgk_1", "hash", "payments:read", NOW.minusSeconds(60), userId);
        key.activate();
        givenCredential(key);
        givenScopeRegistered("payments:read", false, false);
        givenScopeGranted("payments:read", true);

        AccessDecision decision = decide(activePrincipal(), "payments:read");

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reasonCode())
                .isEqualTo(AccessDecision.ReasonCode.CREDENTIAL_STATUS_BLOCKED);
    }

    @Test
    void aPrincipalWithNoAuthoritiesIsDeniedOnRole() {
        AuthenticatedUser noRoles = new AuthenticatedUser(userId, organizationId, UUID.randomUUID(),
                "dev@example.com", "Dev", Set.of(), OrganizationStatus.ACTIVE);
        givenCredential(credential(ApiKeyStatus.ACTIVE, projectId, environmentId));

        AccessDecision decision = decide(noRoles, "payments:read");

        assertThat(decision.reasonCode())
                .isEqualTo(AccessDecision.ReasonCode.ROLE_INSUFFICIENT);
    }

    @Test
    void everyDecisionIsRecordedForLaterExplanation() {
        when(apiKeyRepository.findByIdAndOrganizationId(apiKeyId, organizationId))
                .thenReturn(Optional.empty());

        decide(activePrincipal(), "payments:read");

        verify(decisionRepository).save(any(ApiAccessDecisionRecord.class));
    }

    @Test
    void theRecordedTraceNamesTheFailingFactor() {
        givenCredential(credential(ApiKeyStatus.ACTIVE, projectId, environmentId));
        givenScopeRegistered("payments:read", false, false);
        when(assignmentRepository.findByApiKeyIdAndScopeName(apiKeyId, "payments:read"))
                .thenReturn(Optional.empty());

        AccessDecision decision = decide(activePrincipal(), "payments:read");

        assertThat(decision.factorTrace())
                .contains("CREDENTIAL_UNAVAILABLE=PASS")
                .contains("SCOPE_UNKNOWN=PASS")
                .contains("SCOPE_NOT_ASSIGNED=FAIL(payments:read)");
    }
}
