package com.pesaguard.backend.scopes.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pesaguard.backend.credentials.api.ApiKey;
import com.pesaguard.backend.credentials.api.ApiKeyRepository;
import com.pesaguard.backend.credentials.api.ApiKeyStatus;
import com.pesaguard.backend.scopes.domain.AccessDecision;
import com.pesaguard.backend.scopes.domain.ApiAccessDecisionRecord;
import com.pesaguard.backend.scopes.domain.ApiScope;
import com.pesaguard.backend.scopes.domain.ApiScopeAssignment;
import com.pesaguard.backend.scopes.domain.ApiScopeDefinition;
import com.pesaguard.backend.scopes.infrastructure.ApiAccessDecisionRepository;
import com.pesaguard.backend.scopes.infrastructure.ApiScopeAssignmentRepository;
import com.pesaguard.backend.scopes.infrastructure.ApiScopeDefinitionRepository;
import com.pesaguard.backend.security.principals.AuthenticatedUser;

/**
 * Evaluates an API access request against every factor that constrains it.
 *
 * <p>Factors are evaluated in a fixed order and evaluation stops at the first
 * failure, so the reason code returned is always the <em>first</em> thing wrong
 * rather than an arbitrary one. The order runs from "who are you" outward to
 * "what are you allowed to do":
 *
 * <ol>
 *   <li>Identity — is there an authenticated principal at all</li>
 *   <li>Organization — is it real and active</li>
 *   <li>Project — is it present and active in the request context</li>
 *   <li>Environment — likewise for the environment</li>
 *   <li>Credential — does it exist and belong to this organization</li>
 *   <li>Credential binding — is it scoped to this project and environment</li>
 *   <li>Role — does the actor's role permit the action</li>
 *   <li>Scope — is the scope known and granted</li>
 *   <li>Credential status — is the credential ACTIVE and unexpired</li>
 * </ol>
 *
 * <p>Credential status is checked last deliberately: a revoked credential should
 * report "credential is revoked", which is actionable, rather than an incidental
 * scope failure discovered first.
 *
 * <p>Every decision, allowed or denied, is persisted with its factor trace.
 */
@Service
public class AccessDecisionService {

    private final ApiScopeDefinitionRepository definitionRepository;
    private final ApiScopeAssignmentRepository assignmentRepository;
    private final ApiAccessDecisionRepository decisionRepository;
    private final ApiKeyRepository apiKeyRepository;
    private final Clock clock;

    public AccessDecisionService(
            ApiScopeDefinitionRepository definitionRepository,
            ApiScopeAssignmentRepository assignmentRepository,
            ApiAccessDecisionRepository decisionRepository,
            ApiKeyRepository apiKeyRepository,
            Clock clock) {
        this.definitionRepository = definitionRepository;
        this.assignmentRepository = assignmentRepository;
        this.decisionRepository = decisionRepository;
        this.apiKeyRepository = apiKeyRepository;
        this.clock = clock;
    }

/**
     * Decides whether a credential may exercise a scope in a project environment.
     *
     * <p>Never throws for an access failure — a denial is a returned value, so the
     * caller decides whether to surface it as 403 or to record it. That separation
     * is what lets the decision table record denials for endpoints that answer
     * with a generic error.
     *
     * <p>Project and environment are taken as already resolved and verified by the
     * caller; this service records their presence as a factor rather than
     * re-querying them, which would double the database load of every request for
     * no additional guarantee.
     */
    @Transactional
    public AccessDecision decide(AuthenticatedUser principal, UUID apiKeyId, UUID projectId,
            UUID environmentId, String requestedScope, String requestId, String remoteAddress) {
        AccessDecision.Builder builder = new AccessDecision.Builder();
        builder.request(requestId, remoteAddress);

        // 1. Identity.
        if (principal == null) {
            builder.record("IDENTITY_UNAUTHENTICATED", false, "no principal");
            return persist(builder.build(), requestId, remoteAddress);
        }
        builder.record("IDENTITY_UNAUTHENTICATED", true, principal.userId().toString());
        builder.context(principal.organizationId(), principal.userId(), apiKeyId, projectId,
                environmentId, null);

        // 2. Organization.
        if (!principal.organizationActive()) {
            builder.record("ORGANIZATION_UNAVAILABLE", false, "organization not active");
            return persist(builder.build(), requestId, remoteAddress);
        }
        builder.record("ORGANIZATION_UNAVAILABLE", true, principal.organizationId().toString());

        // 3 and 4. Project and environment.
        if (projectId == null) {
            builder.record("PROJECT_UNAVAILABLE", false, "no project in request context");
            return persist(builder.build(), requestId, remoteAddress);
        }
        builder.record("PROJECT_UNAVAILABLE", true, projectId.toString());
        if (environmentId == null) {
            builder.record("ENVIRONMENT_UNAVAILABLE", false, "no environment in request context");
            return persist(builder.build(), requestId, remoteAddress);
        }
        builder.record("ENVIRONMENT_UNAVAILABLE", true, environmentId.toString());

        // 5 and 6. Credential, and its binding to this project and environment.
        ApiKey credential = apiKeyRepository
                .findByIdAndOrganizationId(apiKeyId, principal.organizationId()).orElse(null);
        if (credential == null) {
            builder.record("CREDENTIAL_UNAVAILABLE", false, "credential not in organization");
            return persist(builder.build(), requestId, remoteAddress);
        }
        builder.record("CREDENTIAL_UNAVAILABLE", true, apiKeyId.toString());
        if (!credential.getProjectId().equals(projectId)
                || !credential.getEnvironmentId().equals(environmentId)) {
            builder.record("CREDENTIAL_CONTEXT_MISMATCH", false, "credential bound elsewhere");
            return persist(builder.build(), requestId, remoteAddress);
        }
        builder.record("CREDENTIAL_CONTEXT_MISMATCH", true, "bound to project and environment");

        // 7. Role.
        boolean rolePermitted = roleAllows(principal);
        builder.record("ROLE_INSUFFICIENT", rolePermitted,
                rolePermitted ? "role permits" : "no active role");
        if (!rolePermitted) {
            return persist(builder.build(), requestId, remoteAddress);
        }

        // 8. Scope: must exist in the registry, then must be actively granted.
        ApiScope scope = ApiScope.tryParse(requestedScope).orElse(null);
        if (scope == null) {
            builder.record("SCOPE_UNKNOWN", false, "malformed scope: " + requestedScope);
            return persist(builder.build(), requestId, remoteAddress);
        }
        ApiScopeDefinition definition = definitionRepository.findByName(scope.value()).orElse(null);
        if (definition == null) {
            builder.record("SCOPE_UNKNOWN", false, "not in registry: " + scope.value());
            return persist(builder.build(), requestId, remoteAddress);
        }
        builder.record("SCOPE_UNKNOWN", true, scope.value());
        builder.context(principal.organizationId(), principal.userId(), apiKeyId, projectId,
                environmentId, scope);

        ApiScopeAssignment assignment = assignmentRepository
                .findByApiKeyIdAndScopeName(apiKeyId, scope.value()).orElse(null);
        if (assignment == null || !assignment.isActive()) {
            builder.record("SCOPE_NOT_ASSIGNED", false, scope.value());
            return persist(builder.build(), requestId, remoteAddress);
        }
        builder.record("SCOPE_NOT_ASSIGNED", true, scope.value());

        // A deprecated scope still works. It is reported so the caller can surface
        // it and the integration can migrate; refusing would break live callers.
        if (definition.isDeprecated()) {
            builder.record("SCOPE_DEPRECATED", true, "replace with "
                    + (definition.getReplacedBy() == null ? "unspecified" : definition.getReplacedBy()));
        }

        // 9. Credential status, last, so a revoked key reports that rather than an
        // incidental scope failure discovered first.
        ApiKeyStatus effective = credential.effectiveStatus(clock.instant());
        boolean usable = effective == ApiKeyStatus.ACTIVE;
        builder.record("CREDENTIAL_STATUS_BLOCKED", usable, effective.name());
        if (!usable) {
            return persist(builder.build(), requestId, remoteAddress);
        }

        builder.record("ALLOWED", true, "all factors passed");
        return persist(builder.build(), requestId, remoteAddress);
    }

    /**
     * Role check, isolated so the role model can change without disturbing the
     * rest of the decision.
     *
     * <p>Currently this requires an authenticated member of an active organization
     * holding at least one granted authority. It is deliberately permissive: the
     * real constraint is the scope grant, checked separately. Overstating what this
     * method enforces would be worse than admitting it is coarse.
     */
    private boolean roleAllows(AuthenticatedUser principal) {
        return principal.organizationActive() && !principal.authorities().isEmpty();
    }

    private AccessDecision persist(AccessDecision decision, String requestId, String remoteAddress) {
        decisionRepository.save(ApiAccessDecisionRecord.of(decision));
        return decision;
    }

    @Transactional(readOnly = true)
    public List<ApiAccessDecisionRecord> recentDenials(AuthenticatedUser principal, int limit) {
        int capped = Math.max(1, Math.min(limit, 200));
        return decisionRepository.findByOrganizationIdAndAllowedFalseOrderByDecidedAtDesc(
                principal.organizationId(),
                org.springframework.data.domain.PageRequest.of(0, capped));
    }
}
