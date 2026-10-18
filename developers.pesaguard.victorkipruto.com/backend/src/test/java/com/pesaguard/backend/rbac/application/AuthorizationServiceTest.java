package com.pesaguard.backend.rbac.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.pesaguard.backend.common.exception.BusinessException;
import com.pesaguard.backend.common.exception.ResourceNotFoundException;
import com.pesaguard.backend.member.domain.UserAccount;
import com.pesaguard.backend.organization.domain.MembershipStatus;
import com.pesaguard.backend.organization.domain.Organization;
import com.pesaguard.backend.organization.domain.OrganizationMembership;
import com.pesaguard.backend.organization.domain.OrganizationRole;
import com.pesaguard.backend.organization.domain.OrganizationStatus;
import com.pesaguard.backend.organization.infrastructure.OrganizationMembershipRepository;
import com.pesaguard.backend.rbac.domain.CustomRole;
import com.pesaguard.backend.rbac.domain.Permission;
import com.pesaguard.backend.rbac.domain.RoleAssignment;
import com.pesaguard.backend.rbac.infrastructure.CustomRoleRepository;
import com.pesaguard.backend.rbac.infrastructure.RoleAssignmentRepository;
import com.pesaguard.backend.security.principals.AuthenticatedUser;

class AuthorizationServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private final UUID organizationId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final OrganizationMembershipRepository membershipRepository = mock(OrganizationMembershipRepository.class);
    private final RoleAssignmentRepository assignmentRepository = mock(RoleAssignmentRepository.class);
    private final CustomRoleRepository roleRepository = mock(CustomRoleRepository.class);
    private final AuthorizationService service =
            new AuthorizationService(membershipRepository, assignmentRepository, roleRepository);

    private AuthenticatedUser principal() {
        return new AuthenticatedUser(userId, organizationId, UUID.randomUUID(),
                "person@example.com", "Person", Set.of("ROLE_VIEWER"), OrganizationStatus.ACTIVE);
    }

    private OrganizationMembership memberMembership() {
        Organization organization = Organization.create("Acme", "acme", UUID.randomUUID(), NOW);
        return OrganizationMembership.member(organization,
                UserAccount.create("person@example.com", "Person", "hash"), OrganizationRole.VIEWER);
    }

    private void givenMembership(OrganizationMembership membership) {
        when(membershipRepository.findByOrganizationIdAndUserId(organizationId, userId))
                .thenReturn(Optional.of(membership));
    }

    @BeforeEach
    void resetMocks() {
        reset(membershipRepository, assignmentRepository, roleRepository);
        when(assignmentRepository.findActiveByOrganizationIdAndUserId(any(), any())).thenReturn(List.of());
    }

    @Test
    void aSuspendedMemberHoldsNoPermissions() {
        OrganizationMembership membership = memberMembership();
        membership.suspend();
        givenMembership(membership);

        assertThat(service.effectivePermissions(principal())).isEmpty();
        assertThatThrownBy(() -> service.requirePermission(principal(), Permission.PROJECT_READ))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("project:read");
    }

    @Test
    void aMissingMembershipHoldsNoPermissions() {
        when(membershipRepository.findByOrganizationIdAndUserId(organizationId, userId))
                .thenReturn(Optional.empty());

        assertThat(service.effectivePermissions(principal())).isEmpty();
        assertThatThrownBy(() -> service.requireActiveMembership(principal()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void customRolePermissionsAreAddedToTheBuiltInRole() {
        givenMembership(memberMembership());
        UUID roleId = UUID.randomUUID();
        when(assignmentRepository.findActiveByOrganizationIdAndUserId(organizationId, userId))
                .thenReturn(List.of(RoleAssignment.grant(organizationId, userId, roleId, userId, NOW)));
        when(roleRepository.findByIdAndOrganizationId(roleId, organizationId))
                .thenReturn(Optional.of(CustomRole.create(organizationId, "Auditor", null,
                        Set.of(Permission.USAGE_READ), userId)));

        Set<Permission> permissions = service.effectivePermissions(principal());
        assertThat(permissions).contains(Permission.PROJECT_READ, Permission.USAGE_READ);
        assertThat(permissions).containsAll(OrganizationRole.VIEWER.permissions());
    }

    @Test
    void archivedCustomRolesGrantNothing() {
        givenMembership(memberMembership());
        UUID roleId = UUID.randomUUID();
        when(assignmentRepository.findActiveByOrganizationIdAndUserId(organizationId, userId))
                .thenReturn(List.of(RoleAssignment.grant(organizationId, userId, roleId, userId, NOW)));
        CustomRole archived = CustomRole.create(organizationId, "Auditor", null,
                Set.of(Permission.USAGE_READ), userId);
        archived.archive();
        when(roleRepository.findByIdAndOrganizationId(roleId, organizationId))
                .thenReturn(Optional.of(archived));

        assertThat(service.effectivePermissions(principal()))
                .doesNotContain(Permission.USAGE_READ)
                .containsExactlyInAnyOrderElementsOf(OrganizationRole.VIEWER.permissions());
    }

    @Test
    void revokedAssignmentsGrantNothing() {
        givenMembership(memberMembership());

        assertThat(service.customRolePermissions(organizationId, userId)).isEmpty();
    }

    @Test
    void catalogIsExposedForClients() {
        assertThat(service.catalogValues()).contains("project:read", "production_access:review");
    }
}