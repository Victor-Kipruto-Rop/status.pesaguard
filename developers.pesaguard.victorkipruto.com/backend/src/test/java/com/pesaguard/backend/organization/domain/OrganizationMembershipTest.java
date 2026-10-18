package com.pesaguard.backend.organization.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.pesaguard.backend.member.domain.UserAccount;

class OrganizationMembershipTest {

    private final Instant now = Instant.parse("2026-01-01T00:00:00Z");

    private Organization organization() {
        return Organization.create("Acme", "acme", UUID.randomUUID(), now);
    }

    private UserAccount user() {
        return UserAccount.create("member@example.com", "Member", "hash");
    }

    @Test
    void ownerMembershipStartsActive() {
        OrganizationMembership membership = OrganizationMembership.owner(organization(), user());

        assertThat(membership.getRole()).isEqualTo(OrganizationRole.OWNER);
        assertThat(membership.getStatus()).isEqualTo(MembershipStatus.ACTIVE);
        assertThat(membership.isActive()).isTrue();
    }

    @Test
    void memberCannotBeCreatedWithOwnerRole() {
        assertThatThrownBy(() -> OrganizationMembership.member(organization(), user(), OrganizationRole.OWNER))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void roleChangeRejectsOwnerAndRequiresOwnershipTransferFirst() {
        OrganizationMembership owner = OrganizationMembership.owner(organization(), user());
        OrganizationMembership member = OrganizationMembership.member(organization(), user(), OrganizationRole.DEVELOPER);

        assertThatThrownBy(() -> owner.changeRole(OrganizationRole.ADMIN))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> member.changeRole(OrganizationRole.OWNER))
                .isInstanceOf(IllegalArgumentException.class);

        member.changeRole(OrganizationRole.ADMIN);
        assertThat(member.getRole()).isEqualTo(OrganizationRole.ADMIN);
    }

    @Test
    void ownerCannotBeSuspendedOrRemoved() {
        OrganizationMembership owner = OrganizationMembership.owner(organization(), user());

        assertThatThrownBy(owner::suspend).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(owner::remove).isInstanceOf(IllegalStateException.class);
        assertThat(owner.getStatus()).isEqualTo(MembershipStatus.ACTIVE);
    }

    @Test
    void removalIsRecordedAsRevokedAndIsReversibleOnlyByAnExplicitActivation() {
        OrganizationMembership member = OrganizationMembership.member(organization(), user(), OrganizationRole.VIEWER);

        member.remove();
        assertThat(member.getStatus()).isEqualTo(MembershipStatus.REVOKED);
        assertThat(member.isActive()).isFalse();

        member.activate();
        assertThat(member.getStatus()).isEqualTo(MembershipStatus.ACTIVE);
    }

    @Test
    void suspensionAndReactivationAreTracked() {
        OrganizationMembership member = OrganizationMembership.member(organization(), user(), OrganizationRole.ADMIN);

        member.suspend();
        assertThat(member.getStatus()).isEqualTo(MembershipStatus.SUSPENDED);
        assertThat(member.isActive()).isFalse();

        member.activate();
        assertThat(member.getStatus()).isEqualTo(MembershipStatus.ACTIVE);
    }

    @Test
    void ownershipTransferPromotesAndDemotesBothMemberships() {
        OrganizationMembership owner = OrganizationMembership.owner(organization(), user());
        OrganizationMembership successor = OrganizationMembership.member(organization(), user(), OrganizationRole.ADMIN);

        owner.relinquishOwnership();
        successor.makeOwner();

        assertThat(owner.getRole()).isEqualTo(OrganizationRole.ADMIN);
        assertThat(owner.getStatus()).isEqualTo(MembershipStatus.ACTIVE);
        assertThat(successor.getRole()).isEqualTo(OrganizationRole.OWNER);
        assertThat(successor.getStatus()).isEqualTo(MembershipStatus.ACTIVE);
    }

    @Test
    void onlyAnOwnerCanRelinquishOwnership() {
        OrganizationMembership member = OrganizationMembership.member(organization(), user(), OrganizationRole.ADMIN);

        assertThatThrownBy(member::relinquishOwnership).isInstanceOf(IllegalStateException.class);
    }
}