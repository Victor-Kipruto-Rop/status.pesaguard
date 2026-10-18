package com.pesaguard.backend.tenancy;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.pesaguard.backend.common.exception.ResourceNotFoundException;
import com.pesaguard.backend.security.principals.AuthenticatedUser;

class TenantGuardTest {

    private final TenantGuard guard = new TenantGuard();
    private final UUID organizationId = UUID.randomUUID();
    private final AuthenticatedUser principal = new AuthenticatedUser(
            UUID.randomUUID(), organizationId, UUID.randomUUID(), "person@example.com", "Person", Set.of("ROLE_OWNER"));

    @Test
    void acceptsMatchingOrganization() {
        assertThatCode(() -> guard.requireOrganization(principal, organizationId))
                .doesNotThrowAnyException();
    }

    @Test
    void hidesCrossOrganizationResourcesAsNotFound() {
        assertThatThrownBy(() -> guard.requireOrganization(principal, UUID.randomUUID()))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("not found");
    }
}
