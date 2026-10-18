package com.pesaguard.backend.project.application;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.pesaguard.backend.organization.domain.OrganizationStatus;
import com.pesaguard.backend.project.infrastructure.ProjectMemberRepository;
import com.pesaguard.backend.security.principals.AuthenticatedUser;

class ProjectAuthorizationTest {

    private final ProjectAuthorization authorization =
            new ProjectAuthorization(mock(ProjectMemberRepository.class));

    @Test
    void ignoresPermissionAuthoritiesWhenCheckingOrganizationRoles() {
        AuthenticatedUser principal = principal(Set.of("projects:read", "ROLE_DEVELOPER", "usage:read"));

        assertFalse(authorization.isOrganizationManager(principal));
    }

    @Test
    void recognizesManagerRoleAlongsidePermissionAuthorities() {
        AuthenticatedUser principal = principal(Set.of("usage:read", "ROLE_OWNER"));

        assertTrue(authorization.isOrganizationManager(principal));
    }

    private static AuthenticatedUser principal(Set<String> authorities) {
        return new AuthenticatedUser(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "developer@example.test", "Developer", authorities, OrganizationStatus.ACTIVE, false, Set.of());
    }
}
