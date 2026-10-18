package com.pesaguard.backend.rbac.application;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pesaguard.backend.common.exception.BusinessException;
import com.pesaguard.backend.common.exception.ResourceNotFoundException;
import com.pesaguard.backend.organization.domain.MembershipStatus;
import com.pesaguard.backend.organization.domain.OrganizationRole;
import com.pesaguard.backend.organization.infrastructure.OrganizationMembershipRepository;
import com.pesaguard.backend.rbac.domain.CustomRole;
import com.pesaguard.backend.rbac.domain.Permission;
import com.pesaguard.backend.rbac.domain.RoleAssignment;
import com.pesaguard.backend.rbac.infrastructure.CustomRoleRepository;
import com.pesaguard.backend.rbac.infrastructure.RoleAssignmentRepository;
import com.pesaguard.backend.security.principals.AuthenticatedUser;

/**
 * Resolves what an actor may actually do.
 *
 * <p>Effective permissions are the union of the member's built-in organization
 * role and every active custom role assigned to them. Resolution always re-reads
 * the membership: a suspended or revoked member holds nothing, even if their
 * session token has not yet expired.
 */
@Service
public class AuthorizationService {

    private final OrganizationMembershipRepository membershipRepository;
    private final RoleAssignmentRepository assignmentRepository;
    private final CustomRoleRepository customRoleRepository;

    public AuthorizationService(
            OrganizationMembershipRepository membershipRepository,
            RoleAssignmentRepository assignmentRepository,
            CustomRoleRepository customRoleRepository) {
        this.membershipRepository = membershipRepository;
        this.assignmentRepository = assignmentRepository;
        this.customRoleRepository = customRoleRepository;
    }

    @Transactional(readOnly = true)
    public Set<Permission> effectivePermissions(AuthenticatedUser principal) {
        Set<Permission> effective = membershipRepository.findByOrganizationIdAndUserId(principal.organizationId(), principal.userId())
                .filter(membership -> membership.getStatus() == MembershipStatus.ACTIVE)
                .map(membership -> {
                    Set<Permission> permissions = EnumSet.noneOf(Permission.class);
                    permissions.addAll(membership.getRole().permissions());
                    permissions.addAll(customRolePermissions(principal.organizationId(), principal.userId()));
                    return permissions;
                })
                .orElseGet(() -> EnumSet.noneOf(Permission.class));
        if (principal.serviceAccount()) {
            effective.removeIf(permission -> !principal.serviceScopes().contains(permission.value()));
        }
        return effective;
    }

    @Transactional(readOnly = true)
    public Set<Permission> customRolePermissions(UUID organizationId, UUID userId) {
        Set<Permission> permissions = EnumSet.noneOf(Permission.class);
        for (RoleAssignment assignment
                : assignmentRepository.findActiveByOrganizationIdAndUserId(organizationId, userId)) {
            customRoleRepository.findByIdAndOrganizationId(assignment.getRoleId(), organizationId)
                    .filter(CustomRole::isActive)
                    .ifPresent(role -> permissions.addAll(role.getPermissions()));
        }
        return permissions;
    }

    public boolean hasPermission(AuthenticatedUser principal, Permission permission) {
        return effectivePermissions(principal).contains(permission);
    }

    public void requirePermission(AuthenticatedUser principal, Permission permission) {
        if (!hasPermission(principal, permission)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "PERMISSION_DENIED",
                    "You do not have the required permission: " + permission.value());
        }
    }

    @Transactional(readOnly = true)
    public OrganizationRole requireActiveMembership(AuthenticatedUser principal) {
        if (principal.serviceAccount()) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "PERMISSION_DENIED",
                    "Service accounts cannot manage organization roles.");
        }
        return membershipRepository.findByOrganizationIdAndUserId(principal.organizationId(), principal.userId())
                .filter(membership -> membership.getStatus() == MembershipStatus.ACTIVE)
                .map(membership -> membership.getRole())
                .orElseThrow(() -> new ResourceNotFoundException("Organization membership"));
    }

    /** Roles the actor may grant: their own built-in permissions, never more. */
    @Transactional(readOnly = true)
    public Set<Permission> grantablePermissions(AuthenticatedUser principal) {
        return effectivePermissions(principal);
    }

    public List<String> catalogValues() {
        return Permission.allValues();
    }
}