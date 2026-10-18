package com.pesaguard.backend.organization.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class OrganizationInvitationTest {

    private final UUID organizationId = UUID.randomUUID();
    private final UUID inviterId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-01-01T00:00:00Z");
    private final String tokenHash = "b".repeat(64);

    private OrganizationInvitation pendingInvitation() {
        return OrganizationInvitation.create(
                organizationId, "invitee@example.com", OrganizationRole.DEVELOPER,
                tokenHash, null, null, now.plusSeconds(3600), inviterId);
    }

    @Test
    void newInvitationIsPendingAndStoresOnlyTheHash() {
        OrganizationInvitation invitation = pendingInvitation();

        assertThat(invitation.getStatus()).isEqualTo(InvitationStatus.PENDING);
        assertThat(invitation.getRole()).isEqualTo(OrganizationRole.DEVELOPER);
        assertThat(invitation.getOrganizationId()).isEqualTo(organizationId);
        assertThat(invitation.getInvitedBy()).isEqualTo(inviterId);
        assertThat(invitation.getAcceptedBy()).isNull();
        assertThat(invitation.getAcceptedAt()).isNull();
        assertThat(tokenHash).hasSize(64);
    }

    @Test
    void acceptanceIsSingleUseAndRecordsTheActor() {
        OrganizationInvitation invitation = pendingInvitation();

        invitation.accept(userId, now.plusSeconds(60));

        assertThat(invitation.getStatus()).isEqualTo(InvitationStatus.ACCEPTED);
        assertThat(invitation.getAcceptedBy()).isEqualTo(userId);
        assertThat(invitation.getAcceptedAt()).isEqualTo(now.plusSeconds(60));
        assertThatThrownBy(() -> invitation.accept(userId, now.plusSeconds(120)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void revokedInvitationCannotBeAccepted() {
        OrganizationInvitation invitation = pendingInvitation();

        invitation.revoke(now.plusSeconds(30));
        assertThat(invitation.getStatus()).isEqualTo(InvitationStatus.REVOKED);
        assertThat(invitation.getRevokedAt()).isEqualTo(now.plusSeconds(30));
        assertThatThrownBy(() -> invitation.accept(userId, now.plusSeconds(60)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void revokeIsIdempotentForSettledInvitations() {
        OrganizationInvitation invitation = pendingInvitation();
        invitation.accept(userId, now.plusSeconds(60));

        invitation.revoke(now.plusSeconds(90));

        assertThat(invitation.getStatus()).isEqualTo(InvitationStatus.ACCEPTED);
        assertThat(invitation.getRevokedAt()).isNull();
    }

    @Test
    void expiredInvitationsCannotBeAcceptedAndAreMarkedExpired() {
        OrganizationInvitation invitation = pendingInvitation();

        invitation.expire(now.plusSeconds(7200));
        assertThat(invitation.getStatus()).isEqualTo(InvitationStatus.EXPIRED);
        assertThatThrownBy(() -> invitation.accept(userId, now.plusSeconds(7200)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void historyRowCapturesTheTransition() {
        Organization organization = Organization.create("Acme", "acme", inviterId, now);
        OrganizationMembership membership = OrganizationMembership.member(
                organization, com.pesaguard.backend.member.domain.UserAccount.create(
                        "member@example.com", "Member", "hash"), OrganizationRole.DEVELOPER);

        membership.suspend();
        OrganizationMembershipHistory history = OrganizationMembershipHistory.record(
                membership, MembershipStatus.ACTIVE, OrganizationRole.DEVELOPER, inviterId, "manual.suspend", now);

        assertThat(history.getMembershipId()).isEqualTo(membership.getId());
        assertThat(history.getOrganizationId()).isEqualTo(organization.getId());
        assertThat(history.getFromStatus()).isEqualTo(MembershipStatus.ACTIVE);
        assertThat(history.getToStatus()).isEqualTo(MembershipStatus.SUSPENDED);
        assertThat(history.getFromRole()).isEqualTo(OrganizationRole.DEVELOPER);
        assertThat(history.getToRole()).isEqualTo(OrganizationRole.DEVELOPER);
        assertThat(history.getActorUserId()).isEqualTo(inviterId);
        assertThat(history.getReason()).isEqualTo("manual.suspend");
    }
}