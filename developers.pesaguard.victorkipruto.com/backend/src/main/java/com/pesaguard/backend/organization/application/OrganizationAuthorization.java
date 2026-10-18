package com.pesaguard.backend.organization.application;

import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.pesaguard.backend.common.exception.ResourceNotFoundException;
import com.pesaguard.backend.organization.domain.MembershipStatus;
import com.pesaguard.backend.organization.domain.OrganizationRole;
import com.pesaguard.backend.organization.infrastructure.OrganizationMembershipRepository;
import com.pesaguard.backend.security.principals.AuthenticatedUser;

@Component
public class OrganizationAuthorization {

    private final OrganizationMembershipRepository membershipRepository;

    public OrganizationAuthorization(OrganizationMembershipRepository membershipRepository) {
        this.membershipRepository = membershipRepository;
    }

    public void requireRole(AuthenticatedUser principal, Set<OrganizationRole> roles) {
        membershipRepository.findByOrganizationIdAndUserId(principal.organizationId(), principal.userId())
                .filter(membership -> membership.getStatus() == MembershipStatus.ACTIVE)
                .filter(membership -> roles.contains(membership.getRole()))
                .orElseThrow(() -> new ResourceNotFoundException("Organization membership"));
    }

    public void requireOwner(AuthenticatedUser principal) {
        requireRole(principal, Set.of(OrganizationRole.OWNER));
    }
}
