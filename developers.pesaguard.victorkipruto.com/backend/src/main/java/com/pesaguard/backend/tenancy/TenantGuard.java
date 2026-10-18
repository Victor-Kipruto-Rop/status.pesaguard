package com.pesaguard.backend.tenancy;

import java.util.UUID;

import com.pesaguard.backend.common.exception.ResourceNotFoundException;
import com.pesaguard.backend.security.principals.AuthenticatedUser;

import org.springframework.stereotype.Component;

@Component
public class TenantGuard {

    public void requireOrganization(AuthenticatedUser principal, UUID resourceOrganizationId) {
        if (!principal.organizationId().equals(resourceOrganizationId)) {
            throw new ResourceNotFoundException("Resource");
        }
    }

    public void requireActiveOrganization(AuthenticatedUser principal) {
        if (!principal.organizationActive()) {
            throw new ResourceNotFoundException("Organization");
        }
    }
}
