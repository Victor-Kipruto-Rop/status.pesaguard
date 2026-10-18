package com.pesaguard.backend.rbac.application;

import java.time.Clock;
import java.util.EnumSet;
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
import com.pesaguard.backend.common.exception.ResourceConflictException;
import com.pesaguard.backend.common.exception.ResourceNotFoundException;
import com.pesaguard.backend.organization.domain.MembershipStatus;
import com.pesaguard.backend.organization.domain.OrganizationRole;
import com.pesaguard.backend.organization.infrastructure.OrganizationMembershipRepository;
import com.pesaguard.backend.rbac.api.CreateRoleRequest;
import com.pesaguard.backend.rbac.api.GrantRoleRequest;
import com.pesaguard.backend.rbac.api.RoleAssignmentView;
import com.pesaguard.backend.rbac.api.RoleView;
import com.pesaguard.backend.rbac.api.UpdateRoleRequest;
import com.pesaguard.backend.rbac.domain.CustomRole;
import com.pesaguard.backend.rbac.domain.Permission;
import com.pesaguard.backend.rbac.domain.RoleAssignment;
import com.pesaguard.backend.rbac.infrastructure.CustomRoleRepository;
import com.pesaguard.backend.rbac.infrastructure.RoleAssignmentRepository;
import com.pesaguard.backend.security.principals.AuthenticatedUser;

/**
 * Custom role lifecycle and assignment.
 *
 * <p>Two rules are enforced on every write:
 * <ul>
 *   <li><b>No privilege escalation.</b> A role may only bundle permissions the
 *       actor already holds, so delegating cannot widen someone's authority past
 *       the delegator's own.</li>
 *   <li><b>Separation of duties.</b> A role may not bundle both
 *       {@code production_access:request} and {@code production_access:review},
 *       which keeps request and approval in different hands.</li>
 * </ul>
 */
@Service
public class RoleManagementService {

    private final CustomRoleRepository roleRepository;
    private final RoleAssignmentRepository assignmentRepository;
    private final OrganizationMembershipRepository membershipRepository;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;
    private final Clock clock;

    public RoleManagementService(
            CustomRoleRepository roleRepository,
            RoleAssignmentRepository assignmentRepository,
            OrganizationMembershipRepository membershipRepository,
            AuthorizationService authorizationService,
            AuditService auditService,
            Clock clock) {
        this.roleRepository = roleRepository;
        this.assignmentRepository = assignmentRepository;
        this.membershipRepository = membershipRepository;
        this.authorizationService = authorizationService;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<RoleView> list(AuthenticatedUser principal) {
        requireRoleManager(principal);
        return roleRepository.findByOrganizationIdOrderByCreatedAtDesc(principal.organizationId()).stream()
                .map(this::toView)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<RoleView> listBuiltIn() {
        return OrganizationRole.all().stream()
                .map(role -> new RoleView(null, role.name(), role.name(),
                        role.permissions().stream().map(Permission::value).sorted().toList(),
                        "BUILT_IN", "ACTIVE", null))
                .toList();
    }

    @Transactional
    public RoleView create(AuthenticatedUser principal, CreateRoleRequest request) {
        requireRoleManager(principal);
        Set<Permission> permissions = validate(principal, request.permissions());
        if (roleRepository.existsByOrganizationIdAndNameIgnoreCase(
                principal.organizationId(), request.name().trim())) {
            throw new ResourceConflictException("ROLE_NAME_EXISTS", "A role with this name already exists.");
        }
        CustomRole role = roleRepository.saveAndFlush(CustomRole.create(
                principal.organizationId(), request.name().trim(), request.description(),
                permissions, principal.userId()));
        audit(principal, "organization.role_created", role.getId(), Map.of("name", role.getName()));
        return toView(role);
    }

    @Transactional
    public RoleView update(AuthenticatedUser principal, UUID roleId, UpdateRoleRequest request) {
        requireRoleManager(principal);
        CustomRole role = requireRole(principal, roleId);
        Set<Permission> permissions = validate(principal, request.permissions());
        role.update(request.name().trim(), request.description(), permissions, principal.userId());
        roleRepository.saveAndFlush(role);
        audit(principal, "organization.role_updated", role.getId(), Map.of());
        return toView(role);
    }

    @Transactional
    public RoleView archive(AuthenticatedUser principal, UUID roleId) {
        requireRoleManager(principal);
        CustomRole role = requireRole(principal, roleId);
        role.archive();
        roleRepository.saveAndFlush(role);
        audit(principal, "organization.role_archived", role.getId(), Map.of());
        return toView(role);
    }

    @Transactional
    public RoleView restore(AuthenticatedUser principal, UUID roleId) {
        requireRoleManager(principal);
        CustomRole role = requireRole(principal, roleId);
        role.restore();
        roleRepository.saveAndFlush(role);
        audit(principal, "organization.role_restored", role.getId(), Map.of());
        return toView(role);
    }

    @Transactional
    public RoleAssignmentView grant(AuthenticatedUser principal, UUID roleId, GrantRoleRequest request) {
        requireRoleManager(principal);
        CustomRole role = requireRole(principal, roleId);
        if (!role.isActive()) {
            throw new BusinessException(HttpStatus.CONFLICT, "ROLE_ARCHIVED",
                    "An archived role cannot be assigned.");
        }
        requireActiveMembership(principal.organizationId(), request.userId());
        validate(principal, role.getPermissions().stream().map(Permission::value).sorted().toList());
        RoleAssignment assignment = assignmentRepository
                .findActiveByOrganizationIdAndUserIdAndRoleId(principal.organizationId(), request.userId(), roleId)
                .orElseGet(() -> RoleAssignment.grant(
                        principal.organizationId(), request.userId(), roleId, principal.userId(), clock.instant()));
        assignmentRepository.saveAndFlush(assignment);
        audit(principal, "organization.role_granted", roleId,
                Map.of("userId", request.userId().toString(), "assignmentId", assignment.getId().toString()));
        return toAssignmentView(assignment, role);
    }

    @Transactional
    public void revokeAssignment(AuthenticatedUser principal, UUID assignmentId) {
        requireRoleManager(principal);
        RoleAssignment assignment = assignmentRepository.findByIdAndOrganizationId(
                        assignmentId, principal.organizationId())
                .orElseThrow(() -> new ResourceNotFoundException("Role assignment"));
        boolean changed = assignment.isActive();
        assignment.revoke(principal.userId(), clock.instant());
        assignmentRepository.saveAndFlush(assignment);
        if (changed) {
            audit(principal, "organization.role_revoked", assignment.getRoleId(),
                    Map.of("userId", assignment.getUserId().toString(),
                            "assignmentId", assignmentId.toString()));
        }
    }

    @Transactional(readOnly = true)
    public List<RoleAssignmentView> assignments(AuthenticatedUser principal) {
        requireRoleManager(principal);
        return assignmentRepository.findAllByOrganizationId(principal.organizationId()).stream()
                .map(assignment -> toAssignmentView(assignment,
                        roleRepository.findByIdAndOrganizationId(assignment.getRoleId(), principal.organizationId())
                                .orElse(null)))
                .toList();
    }

    private Set<Permission> validate(AuthenticatedUser principal, List<String> requested) {
        if (requested == null || requested.isEmpty()) {
            throw invalid("A role must grant at least one permission.");
        }
        Set<Permission> permissions = EnumSet.noneOf(Permission.class);
        for (String value : requested) {
            permissions.add(Permission.parse(value).orElseThrow(() -> invalid("Unknown permission: " + value)));
        }
        Set<Permission> held = authorizationService.effectivePermissions(principal);
        if (!held.containsAll(permissions)) {
            Set<Permission> excess = EnumSet.copyOf(permissions);
            excess.removeAll(held);
            throw new BusinessException(HttpStatus.FORBIDDEN, "PRIVILEGE_ESCALATION_BLOCKED",
                    "You cannot grant permissions you do not hold: "
                            + excess.stream().map(Permission::value).sorted().toList());
        }
        if (OrganizationRole.OWNER.violatesSeparationOfDuties(permissions)) {
            throw invalid("A role may not grant both production_access:request and production_access:review.");
        }
        return permissions;
    }

    private void requireRoleManager(AuthenticatedUser principal) {
        if (!authorizationService.requireActiveMembership(principal).managesRoles()) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ROLE_ADMIN_REQUIRED",
                    "Only an organization owner or administrator can manage roles.");
        }
    }

    private void requireActiveMembership(UUID organizationId, UUID userId) {
        membershipRepository.findByOrganizationIdAndUserId(organizationId, userId)
                .filter(membership -> membership.getStatus() == MembershipStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST, "TARGET_NOT_A_MEMBER",
                        "The target user is not an active member of this organization."));
    }

    private CustomRole requireRole(AuthenticatedUser principal, UUID roleId) {
        return roleRepository.findByIdAndOrganizationId(roleId, principal.organizationId())
                .orElseThrow(() -> new ResourceNotFoundException("Role"));
    }

    private BusinessException invalid(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_PERMISSION", message);
    }

    private void audit(AuthenticatedUser principal, String action, UUID roleId, Map<String, ?> metadata) {
        auditService.append(principal.organizationId(), principal.userId(), action, "role",
                roleId.toString(), RequestContext.currentRequestId(), metadata);
    }

    private RoleView toView(CustomRole role) {
        return new RoleView(role.getId(), role.getName(), role.getDescription(),
                role.getPermissions().stream().map(Permission::value).sorted().toList(),
                "CUSTOM", role.getStatus().name(), role.getUpdatedAt());
    }

    private RoleAssignmentView toAssignmentView(RoleAssignment assignment, CustomRole role) {
        return new RoleAssignmentView(assignment.getId(), assignment.getUserId(),
                role == null ? null : role.getName(), assignment.getAssignedAt(),
                assignment.getRevokedAt(), assignment.isActive());
    }
}