package com.pesaguard.backend.scopes.application;

import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pesaguard.backend.audit.application.AuditService;
import com.pesaguard.backend.common.api.RequestContext;
import com.pesaguard.backend.common.exception.BusinessException;
import com.pesaguard.backend.common.exception.ResourceNotFoundException;
import com.pesaguard.backend.rbac.application.AuthorizationService;
import com.pesaguard.backend.rbac.domain.Permission;
import com.pesaguard.backend.scopes.api.ScopeAssignmentView;
import com.pesaguard.backend.scopes.api.ScopeView;
import com.pesaguard.backend.scopes.domain.ApiScope;
import com.pesaguard.backend.scopes.domain.ApiScopeAssignment;
import com.pesaguard.backend.scopes.domain.ApiScopeDefinition;
import com.pesaguard.backend.scopes.domain.ApiScopeRestriction;
import com.pesaguard.backend.scopes.infrastructure.ApiScopeAssignmentRepository;
import com.pesaguard.backend.scopes.infrastructure.ApiScopeDefinitionRepository;
import com.pesaguard.backend.scopes.infrastructure.ApiScopeRestrictionRepository;
import com.pesaguard.backend.security.principals.AuthenticatedUser;

/**
 * Scope registry management: catalog reads, scope validation, assignment, and
 * operator annotation of restricted and deprecated scopes.
 *
 * <p>The registry is not user-extensible. An unknown scope is rejected rather
 * than created, so a typo fails the integration that made it instead of creating a
 * scope that grants nothing and looks legitimate in a listing.
 */
@Service
public class ScopeRegistryService {

    private static final int MAX_SCOPES_PER_REQUEST = 20;

    private final ApiScopeDefinitionRepository definitionRepository;
    private final ApiScopeRestrictionRepository restrictionRepository;
    private final ApiScopeAssignmentRepository assignmentRepository;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;
    private final Clock clock;
    private final com.pesaguard.backend.credentials.api.ApiKeyRepository apiKeyRepository;

    public ScopeRegistryService(
            ApiScopeDefinitionRepository definitionRepository,
            ApiScopeRestrictionRepository restrictionRepository,
            ApiScopeAssignmentRepository assignmentRepository,
            AuthorizationService authorizationService,
            AuditService auditService,
            Clock clock,
            com.pesaguard.backend.credentials.api.ApiKeyRepository apiKeyRepository) {
        this.definitionRepository = definitionRepository;
        this.restrictionRepository = restrictionRepository;
        this.assignmentRepository = assignmentRepository;
        this.authorizationService = authorizationService;
        this.auditService = auditService;
        this.clock = clock;
        this.apiKeyRepository = apiKeyRepository;
    }
@Transactional(readOnly = true)
    public List<ScopeView> catalog(AuthenticatedUser principal, boolean includeDeprecated) {
        // The catalog contains globally assignable scope metadata, not credential
        // inventory. A role allowed to create a key needs the catalog to request
        // its scopes even when it cannot read existing keys.
        if (!authorizationService.hasPermission(principal, Permission.CREDENTIAL_READ)
                && !authorizationService.hasPermission(principal, Permission.CREDENTIAL_CREATE)) {
            authorizationService.requirePermission(principal, Permission.CREDENTIAL_READ);
        }
        List<ApiScopeDefinition> definitions = definitionRepository.findAllByOrderByCategoryAscNameAsc();
        if (!includeDeprecated) {
            definitions = definitions.stream().filter(definition -> !definition.isDeprecated()).toList();
        }
        Map<String, ApiScopeRestriction> restrictions = restrictionsByScope();
        return ScopeView.all(definitions, restrictions::get);
    }

    @Transactional(readOnly = true)
    public ScopeView get(AuthenticatedUser principal, String scopeName) {
        authorizationService.requirePermission(principal, Permission.CREDENTIAL_READ);
        ApiScopeDefinition definition = requireDefinition(scopeName);
        return ScopeView.from(definition,
                restrictionRepository.findByScopeName(definition.getName()).orElse(null));
    }

    /**
     * Validates a caller-supplied scope set against the registry.
     *
     * <p>Malformed syntax and unknown scope are reported separately, because they
     * mean different things to whoever has to fix them: one is a bug in the
     * integrator's code, the other is a scope that never existed. Collapsing them
     * would leave them guessing which they hit.
     */
    @Transactional(readOnly = true)
    public ScopeValidation validate(Set<String> requested) {
        if (requested == null || requested.isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "SCOPES_REQUIRED",
                    "At least one scope must be requested.");
        }
        if (requested.size() > MAX_SCOPES_PER_REQUEST) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "TOO_MANY_SCOPES",
                    "At most " + MAX_SCOPES_PER_REQUEST + " scopes may be requested at once.");
        }
        Set<ApiScope> parsed = new LinkedHashSet<>();
        Set<String> deprecated = new LinkedHashSet<>();
        for (String candidate : requested) {
            ApiScope scope = ApiScope.tryParse(candidate).orElseThrow(() -> new BusinessException(
                    HttpStatus.BAD_REQUEST, "SCOPE_MALFORMED",
                    "Scope is not in the required resource:action form: " + candidate));
            ApiScopeDefinition definition = definitionRepository.findByName(scope.value())
                    .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST, "SCOPE_UNKNOWN",
                            "Scope is not in the registry: " + scope.value()));
            if (definition.isDeprecated()) {
                deprecated.add(scope.value());
            }
            if (!definition.isApiKeyAssignable()) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "SCOPE_UNAVAILABLE",
                        "Scope is not currently available to API keys: " + scope.value());
            }
            parsed.add(scope);
        }
        return new ScopeValidation(parsed, deprecated);
    }

    /**
     * Grants scopes to a credential, recording who granted each one and at which
     * registry version. Re-granting an existing scope is a no-op rather than an
     * error, so a retried request does not fabricate a second grant.
     *
     * <p>Least-privilege reconciliation: the assignment table is the grant
     * record, but the credential's {@code scopes} column is what the API-key
     * authentication path reads. Both are rewritten here in the same
     * transaction so a grant takes effect immediately and revocation below
     * removes it immediately. A grant for a non-existent key fails closed.
     */
    @Transactional
    public List<ScopeAssignmentView> assign(AuthenticatedUser principal, UUID apiKeyId,
            Set<String> requested) {
        authorizationService.requirePermission(principal, Permission.CREDENTIAL_CREATE);
        ScopeValidation validation = validate(requested);
        com.pesaguard.backend.credentials.api.ApiKey key = apiKeyRepository
                .findById(apiKeyId)
                .filter(candidate -> candidate.getOrganizationId().equals(principal.organizationId()))
                .orElseThrow(() -> new ResourceNotFoundException("API key"));
        List<ScopeAssignmentView> granted = new java.util.ArrayList<>();
        for (ApiScope scope : validation.scopes()) {
            ApiScopeDefinition definition = requireDefinition(scope.value());
            ApiScopeAssignment assignment = assignmentRepository
                    .findByApiKeyIdAndScopeName(apiKeyId, scope.value())
                    .orElseGet(() -> ApiScopeAssignment.grant(principal.organizationId(), apiKeyId,
                            definition, principal.userId()));
            assignmentRepository.saveAndFlush(assignment);
            auditService.append(principal.organizationId(), principal.userId(), "api_scope.granted",
                    "api_scope", scope.value(), RequestContext.currentRequestId(),
                    Map.of("apiKeyId", apiKeyId.toString(),
                            "scopeVersion", Integer.toString(definition.getVersion())));
            granted.add(ScopeAssignmentView.from(assignment));
        }
        rewriteKeyScopesFromAssignments(key);
        return granted;
    }

    /** Revokes a single scope grant. The credential itself is left untouched. */
    @Transactional
    public void revokeAssignment(AuthenticatedUser principal, UUID assignmentId, String reason) {
        authorizationService.requirePermission(principal, Permission.CREDENTIAL_REVOKE);
        ApiScopeAssignment assignment = assignmentRepository.findById(assignmentId)
                .filter(candidate -> candidate.getOrganizationId().equals(principal.organizationId()))
                .orElseThrow(() -> new ResourceNotFoundException("Scope assignment"));
        assignment.revoke(clock.instant(), principal.userId(), reason);
        assignmentRepository.saveAndFlush(assignment);
        auditService.append(principal.organizationId(), principal.userId(), "api_scope.revoked",
                "api_scope", assignment.getScopeName(), RequestContext.currentRequestId(),
                Map.of("assignmentId", assignmentId.toString()));
        apiKeyRepository.findById(assignment.getApiKeyId())
                .filter(candidate -> candidate.getOrganizationId().equals(principal.organizationId()))
                .ifPresent(this::rewriteKeyScopesFromAssignments);
    }

    /**
     * Rewrites the credential's scopes column from its active assignments so
     * the authentication path and the grant record can never drift apart.
     * Active assignments only; sorted and deduplicated for determinism.
     */
    @Transactional
    public void reconcileKeyScopes(AuthenticatedUser principal, UUID apiKeyId) {
        authorizationService.requirePermission(principal, Permission.CREDENTIAL_READ);
        com.pesaguard.backend.credentials.api.ApiKey key = apiKeyRepository
                .findById(apiKeyId)
                .filter(candidate -> candidate.getOrganizationId().equals(principal.organizationId()))
                .orElseThrow(() -> new ResourceNotFoundException("API key"));
        rewriteKeyScopesFromAssignments(key);
    }

    private void rewriteKeyScopesFromAssignments(com.pesaguard.backend.credentials.api.ApiKey key) {
        String encoded = assignmentRepository.findByApiKeyIdOrderByGrantedAtDesc(key.getId()).stream()
                .filter(assignment -> assignment.isActive())
                .map(ApiScopeAssignment::getScopeName)
                .distinct()
                .sorted()
                .collect(java.util.stream.Collectors.joining(","));
        key.updateScopes(encoded);
        apiKeyRepository.saveAndFlush(key);
    }

    @Transactional(readOnly = true)
    public List<ScopeAssignmentView> assignmentsFor(AuthenticatedUser principal, UUID apiKeyId) {
        authorizationService.requirePermission(principal, Permission.CREDENTIAL_READ);
        return assignmentRepository.findByApiKeyIdOrderByGrantedAtDesc(apiKeyId).stream()
                .map(ScopeAssignmentView::from)
                .toList();
    }
/**
     * Marks a scope deprecated in favour of a replacement.
     *
     * <p>The scope keeps working. Silently revoking it would break every live
     * integration that has not yet migrated, and an outage is not a deprecation
     * strategy.
     */
    @Transactional
    public ScopeView deprecate(AuthenticatedUser principal, String scopeName,
            String replacement, String reason) {
        authorizationService.requirePermission(principal, Permission.CREDENTIAL_UPDATE);
        ApiScopeDefinition definition = requireDefinition(scopeName);
        if (replacement != null && !replacement.isBlank()
                && definitionRepository.findByName(replacement.trim()).isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "SCOPE_UNKNOWN",
                    "Replacement scope is not in the registry: " + replacement);
        }
        definition.deprecate(replacement, reason);
        definitionRepository.saveAndFlush(definition);
        auditService.append(principal.organizationId(), principal.userId(), "api_scope.deprecated",
                "api_scope", scopeName, RequestContext.currentRequestId(),
                Map.of("replacedBy", replacement == null ? "" : replacement));
        return ScopeView.from(definition,
                restrictionRepository.findByScopeName(scopeName).orElse(null));
    }

    /**
     * Restricts a scope further. A reason is mandatory: an unexplained restriction
     * is indistinguishable, months later, from a bug.
     */
    @Transactional
    public ScopeView restrict(AuthenticatedUser principal, String scopeName, String reason) {
        authorizationService.requirePermission(principal, Permission.CREDENTIAL_UPDATE);
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "REASON_REQUIRED",
                    "Restricting a scope requires a stated reason.");
        }
        ApiScopeDefinition definition = requireDefinition(scopeName);
        definition.restrict(reason);
        definitionRepository.saveAndFlush(definition);
        auditService.append(principal.organizationId(), principal.userId(), "api_scope.restricted",
                "api_scope", scopeName, RequestContext.currentRequestId(), Map.of());
        return ScopeView.from(definition,
                restrictionRepository.findByScopeName(scopeName).orElse(null));
    }

    public ApiScopeDefinition requireDefinition(String scopeName) {
        return definitionRepository.findByName(scopeName)
                .orElseThrow(() -> new ResourceNotFoundException("Scope"));
    }

    private Map<String, ApiScopeRestriction> restrictionsByScope() {
        Map<String, ApiScopeRestriction> byScope = new java.util.HashMap<>();
        for (ApiScopeRestriction restriction : restrictionRepository.findAll()) {
            byScope.put(restriction.getScopeName(), restriction);
        }
        return byScope;
    }

    /** The outcome of validating a requested scope set. */
    public record ScopeValidation(Set<ApiScope> scopes, Set<String> deprecatedScopes) {
    }
}
