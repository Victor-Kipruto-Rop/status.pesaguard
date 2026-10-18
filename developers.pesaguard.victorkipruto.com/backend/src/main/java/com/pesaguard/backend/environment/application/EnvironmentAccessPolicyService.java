package com.pesaguard.backend.environment.application;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pesaguard.backend.audit.application.AuditService;
import com.pesaguard.backend.common.api.RequestContext;
import com.pesaguard.backend.common.exception.BusinessException;
import com.pesaguard.backend.common.exception.ResourceNotFoundException;
import com.pesaguard.backend.environment.api.EnvironmentAccessPolicyView;
import com.pesaguard.backend.environment.api.UpsertEnvironmentAccessPolicyRequest;
import com.pesaguard.backend.environment.domain.EnvironmentAccessPolicy;
import com.pesaguard.backend.environment.domain.EnvironmentAccessSubjectType;
import com.pesaguard.backend.environment.domain.EnvironmentPermission;
import com.pesaguard.backend.environment.domain.ProjectEnvironment;
import com.pesaguard.backend.environment.infrastructure.EnvironmentAccessPolicyRepository;
import com.pesaguard.backend.environment.infrastructure.ProjectEnvironmentRepository;
import com.pesaguard.backend.organization.domain.OrganizationRole;
import com.pesaguard.backend.project.domain.ProjectMemberRole;
import com.pesaguard.backend.project.domain.ProjectMemberStatus;
import com.pesaguard.backend.project.infrastructure.ProjectMemberRepository;
import com.pesaguard.backend.project.application.ProjectAuthorization;
import com.pesaguard.backend.rbac.application.AuthorizationService;
import com.pesaguard.backend.rbac.domain.Permission;
import com.pesaguard.backend.security.authentication.IpRangeMatcher;
import com.pesaguard.backend.security.principals.AuthenticatedUser;

@Service
public class EnvironmentAccessPolicyService {

    private final EnvironmentAccessPolicyRepository policyRepository;
    private final ProjectEnvironmentRepository environmentRepository;
    private final ProjectMemberRepository memberRepository;
    private final AuthorizationService authorizationService;
    private final IpRangeMatcher ipRangeMatcher;
    private final AuditService auditService;
    private final ProjectAuthorization projectAuthorization;

    public EnvironmentAccessPolicyService(EnvironmentAccessPolicyRepository policyRepository,
            ProjectEnvironmentRepository environmentRepository, ProjectMemberRepository memberRepository,
            AuthorizationService authorizationService, IpRangeMatcher ipRangeMatcher, AuditService auditService,
            ProjectAuthorization projectAuthorization) {
        this.policyRepository = policyRepository;
        this.environmentRepository = environmentRepository;
        this.memberRepository = memberRepository;
        this.authorizationService = authorizationService;
        this.ipRangeMatcher = ipRangeMatcher;
        this.auditService = auditService;
        this.projectAuthorization = projectAuthorization;
    }

    @Transactional(readOnly = true)
    public List<EnvironmentAccessPolicyView> list(AuthenticatedUser principal, UUID projectId, UUID environmentId) {
        authorizationService.requirePermission(principal, Permission.ENVIRONMENT_READ);
        projectAuthorization.requireProjectRead(principal, projectId);
        ProjectEnvironment environment = requireEnvironment(principal, projectId, environmentId);
        requireAccess(principal, environment, EnvironmentPermission.MANAGE_POLICIES);
        return policyRepository.findByEnvironmentIdOrderBySubjectTypeAscSubjectRoleAsc(environmentId)
                .stream().map(EnvironmentAccessPolicyView::from).toList();
    }

    @Transactional
    public EnvironmentAccessPolicyView upsert(AuthenticatedUser principal, UUID projectId, UUID environmentId,
            UpsertEnvironmentAccessPolicyRequest request) {
        authorizationService.requirePermission(principal, Permission.ENVIRONMENT_UPDATE);
        projectAuthorization.requireProjectManage(principal, projectId);
        ProjectEnvironment environment = environmentRepository.findByIdAndOrganizationIdAndProjectIdForUpdate(
                environmentId, principal.organizationId(), projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Environment"));
        requireAccess(principal, environment, EnvironmentPermission.MANAGE_POLICIES);
        validateRole(request.subjectType(), request.subjectRole());
        ipRangeMatcher.validate(request.ipAllowlist());
        String role = request.subjectRole().trim();
        Optional<EnvironmentAccessPolicy> existing = policyRepository
                .findByEnvironmentIdAndSubjectTypeAndSubjectRole(environmentId, request.subjectType(), role);
        EnvironmentAccessPolicy policy;
        String action;
        if (existing.isPresent()) {
            policy = existing.get();
            policy.grant(request.permissions(), request.ipAllowlist());
            action = "environment.access_policy.updated";
        } else {
            policy = EnvironmentAccessPolicy.create(principal.organizationId(), projectId, environmentId,
                    request.subjectType(), role, request.permissions(), request.ipAllowlist(), principal.userId());
            action = "environment.access_policy.created";
        }
        policy = policyRepository.saveAndFlush(policy);
        auditService.append(principal.organizationId(), principal.userId(), action,
                "environment_access_policy", policy.getId().toString(), RequestContext.currentRequestId(),
                java.util.Map.of("environmentId", environment.getId().toString(),
                        "subjectType", policy.getSubjectType().name(),
                        "subjectRole", policy.getSubjectRole(),
                        "permissions", policy.getPermissions().stream().map(Enum::name).sorted().toList()));
        return EnvironmentAccessPolicyView.from(policy);
    }

    @Transactional
    public void delete(AuthenticatedUser principal, UUID projectId, UUID environmentId, UUID policyId) {
        authorizationService.requirePermission(principal, Permission.ENVIRONMENT_UPDATE);
        projectAuthorization.requireProjectManage(principal, projectId);
        ProjectEnvironment environment = requireEnvironment(principal, projectId, environmentId);
        requireAccess(principal, environment, EnvironmentPermission.MANAGE_POLICIES);
        EnvironmentAccessPolicy policy = policyRepository.findById(policyId)
                .filter(item -> item.getEnvironmentId().equals(environmentId)
                        && item.getProjectId().equals(projectId)
                        && item.getOrganizationId().equals(principal.organizationId()))
                .orElseThrow(() -> new ResourceNotFoundException("Environment access policy"));
        policyRepository.delete(policy);
        auditService.append(principal.organizationId(), principal.userId(), "environment.access_policy.deleted",
                "environment_access_policy", policyId.toString(), RequestContext.currentRequestId(),
                java.util.Map.of("environmentId", environmentId.toString(),
                        "subjectType", policy.getSubjectType().name(), "subjectRole", policy.getSubjectRole()));
    }

    @Transactional(readOnly = true)
    public boolean canAccess(AuthenticatedUser principal, ProjectEnvironment environment,
            EnvironmentPermission permission) {
        if (isOrganizationAdministrator(principal)) {
            return true;
        }
        List<EnvironmentAccessPolicy> policies = policyRepository
                .findByEnvironmentIdOrderBySubjectTypeAscSubjectRoleAsc(environment.getId());
        if (policies.isEmpty()) {
            return true;
        }
        Optional<OrganizationRole> organizationRole = organizationRole(principal);
        if (organizationRole.isPresent()) {
            Optional<EnvironmentAccessPolicy> rolePolicy = findPolicy(policies,
                    EnvironmentAccessSubjectType.ORGANIZATION, organizationRole.get().name());
            if (rolePolicy.isPresent()) {
                return grants(rolePolicy.get(), permission);
            }
        }
        Optional<ProjectMemberRole> memberRole = memberRepository
                .findByProjectIdAndUserId(environment.getProjectId(), principal.userId())
                .filter(member -> member.getStatus() == ProjectMemberStatus.ACTIVE)
                .map(member -> member.getRole());
        if (memberRole.isPresent()) {
            Optional<EnvironmentAccessPolicy> rolePolicy = findPolicy(policies,
                    EnvironmentAccessSubjectType.PROJECT_MEMBER, memberRole.get().name());
            if (rolePolicy.isPresent()) {
                return grants(rolePolicy.get(), permission);
            }
        }
        return false;
    }

    public void requireAccess(AuthenticatedUser principal, ProjectEnvironment environment,
            EnvironmentPermission permission) {
        if (!canAccess(principal, environment, permission)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ENVIRONMENT_ACCESS_DENIED",
                    "Your role is not authorized for this environment operation.");
        }
    }

    public boolean hasPoliciesForProject(UUID projectId) {
        return policyRepository.existsByProjectId(projectId);
    }

    public boolean hasPoliciesForProjects(Set<UUID> projectIds) {
        return !projectIds.isEmpty() && policyRepository.existsByProjectIdIn(projectIds);
    }

    public boolean isOrganizationAdministratorRole(AuthenticatedUser principal) {
        return isOrganizationAdministrator(principal);
    }

    private boolean grants(EnvironmentAccessPolicy policy, EnvironmentPermission permission) {
        boolean granted = policy.grants(permission)
                || permission == EnvironmentPermission.READ
                        && (policy.grants(EnvironmentPermission.WRITE)
                                || policy.grants(EnvironmentPermission.DEPLOY)
                                || policy.grants(EnvironmentPermission.ROTATE_CREDENTIALS)
                                || policy.grants(EnvironmentPermission.MANAGE_POLICIES));
        return granted && (policy.getIpAllowlist().isEmpty()
                || ipRangeMatcher.isAllowed(RequestContext.currentRemoteAddress(), policy.getIpAllowlist()));
    }

    private static Optional<EnvironmentAccessPolicy> findPolicy(List<EnvironmentAccessPolicy> policies,
            EnvironmentAccessSubjectType subjectType, String role) {
        return policies.stream().filter(policy -> policy.getSubjectType() == subjectType
                && policy.getSubjectRole().equals(role)).findFirst();
    }

    private ProjectEnvironment requireEnvironment(AuthenticatedUser principal, UUID projectId, UUID environmentId) {
        return environmentRepository.findByIdAndOrganizationIdAndProjectId(environmentId, principal.organizationId(), projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Environment"));
    }

    private static Optional<OrganizationRole> organizationRole(AuthenticatedUser principal) {
        return Arrays.stream(OrganizationRole.values())
                .filter(role -> principal.authorities().contains("ROLE_" + role.name())
                        || principal.authorities().contains(role.name()))
                .findFirst();
    }

    private static boolean isOrganizationAdministrator(AuthenticatedUser principal) {
        return principal.authorities().stream().map(authority ->
                authority.startsWith("ROLE_") ? authority.substring(5) : authority)
                .anyMatch(role -> role.equals(OrganizationRole.OWNER.name())
                        || role.equals(OrganizationRole.ADMIN.name()));
    }

    private static void validateRole(EnvironmentAccessSubjectType subjectType, String subjectRole) {
        if (subjectRole == null || subjectRole.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "ENVIRONMENT_POLICY_ROLE_INVALID",
                    "A valid role is required for an environment access policy.");
        }
        boolean valid = switch (subjectType) {
            case ORGANIZATION -> Arrays.stream(OrganizationRole.values())
                    .anyMatch(role -> role.name().equals(subjectRole.trim()));
            case PROJECT_MEMBER -> Arrays.stream(ProjectMemberRole.values())
                    .anyMatch(role -> role.name().equals(subjectRole.trim()));
        };
        if (!valid) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "ENVIRONMENT_POLICY_ROLE_INVALID",
                    "The role does not belong to the selected policy subject type.");
        }
    }
}
