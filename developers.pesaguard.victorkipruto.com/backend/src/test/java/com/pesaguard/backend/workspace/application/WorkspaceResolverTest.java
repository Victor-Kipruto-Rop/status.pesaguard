package com.pesaguard.backend.workspace.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.pesaguard.backend.common.exception.ResourceNotFoundException;
import com.pesaguard.backend.member.domain.UserAccount;
import com.pesaguard.backend.organization.domain.Organization;
import com.pesaguard.backend.organization.domain.OrganizationMembership;
import com.pesaguard.backend.organization.domain.OrganizationRole;
import com.pesaguard.backend.organization.infrastructure.OrganizationMembershipRepository;
import com.pesaguard.backend.security.principals.AuthenticatedUser;

/**
 * Workspace resolution, which is the tenant-isolation boundary for the whole
 * {@code /api/v1/workspaces} surface.
 *
 * <p>Almost every test here asserts a refusal. A workspace id arrives from a URL
 * and a header, both entirely under the caller's control, so the property that
 * matters is that an id the caller does not hold never becomes an organizationId
 * on a principal. Getting that wrong is cross-tenant data access, and none of it
 * would throw.
 */
class WorkspaceResolverTest {

    private static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");

    private final OrganizationMembershipRepository membershipRepository =
            mock(OrganizationMembershipRepository.class);

    private WorkspaceResolver resolver;
    private AuthenticatedUser caller;

    @BeforeEach
    void setUp() {
        resolver = new WorkspaceResolver(membershipRepository);
        caller = new AuthenticatedUser(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "person@example.com", "Person", Set.of("ROLE_OWNER"));
    }

    /**
     * A membership in a real workspace owned by the caller.
     *
     * <p>{@code Organization.create} assigns its own random id, so the caller
     * cannot dictate the organization's id the way it can the membership's. The
     * organisation is therefore created first and its actual id used to key the
     * stub -- keying on an unrelated id would silently never match, and the test
     * would pass or fail for reasons unrelated to the behaviour under test.
     */
    private OrganizationMembership membership(OrganizationRole role) {
        UserAccount user = UserAccount.create("person@example.com", "Person", "encoded-hash");
        Organization organization = Organization.create("Acme", "acme-" + UUID.randomUUID(),
                user.getId(), NOW);
        return role == OrganizationRole.OWNER
                ? OrganizationMembership.owner(organization, user)
                : OrganizationMembership.member(organization, user, role);
    }

    private void givenMembership(UUID organizationId, OrganizationMembership membership) {
        when(membershipRepository.findByOrganizationIdAndUserId(eq(organizationId), eq(caller.userId())))
                .thenReturn(Optional.of(membership));
    }

    /** Stubs the lookup using the membership's own organization id. */
    private void givenOwnMembership(OrganizationMembership membership) {
        givenMembership(membership.getOrganization().getId(), membership);
    }

    private void givenNoMembership(UUID organizationId) {
        when(membershipRepository.findByOrganizationIdAndUserId(eq(organizationId), eq(caller.userId())))
                .thenReturn(Optional.empty());
    }

    @Test
    void aWorkspaceTheCallerDoesNotBelongToIsRefused() {
        // The core tenant-isolation assertion: a guessed id must not resolve.
        UUID foreign = UUID.randomUUID();
        givenNoMembership(foreign);

        assertThatThrownBy(() -> resolver.requireMembership(caller, foreign))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void anUnknownWorkspaceIsIndistinguishableFromAForbiddenOne() {
        // Both answer "not found" on purpose. Distinguishing them would let an
        // authenticated user enumerate which workspaces exist by guessing ids.
        UUID unknown = UUID.randomUUID();
        UUID foreign = UUID.randomUUID();
        givenNoMembership(unknown);
        givenNoMembership(foreign);

        assertThatThrownBy(() -> resolver.requireMembership(caller, unknown))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> resolver.requireMembership(caller, foreign))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void aNullWorkspaceIsRefusedRatherThanDefaulted() {
        // Defaulting a missing id to "the session's workspace" would let a caller
        // omit the path segment and be silently served a different workspace than
        // the one they asked for.
        assertThatThrownBy(() -> resolver.requireMembership(caller, null))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void authoritiesComeFromTheTargetWorkspaceNotTheSession() {
        // The session says OWNER. In this workspace the caller is only a VIEWER.
        // Carrying the session's authorities across would hand them OWNER actions
        // on the strength of a token issued for a different workspace.
        OrganizationMembership viewer = membership(OrganizationRole.VIEWER);
        givenOwnMembership(viewer);
        UUID workspaceId = viewer.getOrganization().getId();

        AuthenticatedUser scoped = resolver.principalFor(caller, workspaceId);

        assertThat(scoped.authorities()).containsExactly("ROLE_VIEWER");
        assertThat(scoped.authorities()).doesNotContain("ROLE_OWNER");
        assertThat(scoped.organizationId()).isEqualTo(workspaceId);
    }

    @Test
    void aRevokedMembershipHoldsNoAccess() {
        // The session may still be live; the membership is what grants access.
        OrganizationMembership revoked = membership(OrganizationRole.ADMIN);
        revoked.remove();
        givenOwnMembership(revoked);
        UUID workspaceId = revoked.getOrganization().getId();

        assertThatThrownBy(() -> resolver.requireMembership(caller, workspaceId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void aSuspendedMembershipHoldsNoAccess() {
        OrganizationMembership suspended = membership(OrganizationRole.ADMIN);
        suspended.suspend();
        givenOwnMembership(suspended);
        UUID workspaceId = suspended.getOrganization().getId();

        assertThatThrownBy(() -> resolver.requireMembership(caller, workspaceId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void aDeletedWorkspaceResolvesToNothing() {
        OrganizationMembership inDeleted = membership(OrganizationRole.ADMIN);
        inDeleted.getOrganization().delete(NOW);
        givenOwnMembership(inDeleted);
        UUID workspaceId = inDeleted.getOrganization().getId();

        assertThatThrownBy(() -> resolver.requireMembership(caller, workspaceId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void theSessionWorkspaceIsAcceptedWithoutAMembershipLookup() {
        // Agreeing with the session must not cost a query, and must not fail for a
        // workspace the session already proves the caller is in.
        assertThat(resolver.principalForHeader(caller, caller.organizationId().toString()))
                .isSameAs(caller);
        assertThat(resolver.principalForHeader(caller, null)).isSameAs(caller);
        assertThat(resolver.principalForHeader(caller, "   ")).isSameAs(caller);
    }

    @Test
    void aForgedWorkspaceHeaderIsRefusedRatherThanTrusted() {
        UUID foreign = UUID.randomUUID();
        givenNoMembership(foreign);

        assertThatThrownBy(() -> resolver.principalForHeader(caller, foreign.toString()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void aMalformedWorkspaceHeaderIsRefusedAsAnUnknownWorkspace() {
        // Answered as "not found" rather than as a parse error, so a caller cannot
        // use the response shape to learn how the server parses ids.
        assertThatThrownBy(() -> resolver.principalForHeader(caller, "not-a-uuid"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void onlyTheCallersOwnWorkspacesAreListed() {
        OrganizationMembership owner = membership(OrganizationRole.OWNER);
        when(membershipRepository.findAllActiveByUserId(eq(caller.userId())))
                .thenReturn(List.of(owner));

        assertThat(resolver.membershipsFor(caller))
                .extracting(Organization::getId)
                .containsExactly(owner.getOrganization().getId());
    }

    @Test
    void theCallerIdentityIsCarriedThroughUnchanged() {
        // Scoping a request must not change who is acting: userId, session and
        // email belong to the person, not to the workspace.
        OrganizationMembership admin = membership(OrganizationRole.ADMIN);
        givenOwnMembership(admin);
        UUID workspaceId = admin.getOrganization().getId();

        AuthenticatedUser scoped = resolver.principalFor(caller, workspaceId);

        assertThat(scoped.userId()).isEqualTo(caller.userId());
        assertThat(scoped.sessionId()).isEqualTo(caller.sessionId());
        assertThat(scoped.email()).isEqualTo(caller.email());
    }

    @Test
    void anActiveMembershipResolvesToItsWorkspaceAndRole() {
        OrganizationMembership developer = membership(OrganizationRole.DEVELOPER);
        givenOwnMembership(developer);
        UUID workspaceId = developer.getOrganization().getId();

        assertThat(resolver.requireMembership(caller, workspaceId).getOrganization().getId())
                .isEqualTo(workspaceId);
        assertThat(resolver.roleIn(caller, workspaceId)).isEqualTo(OrganizationRole.DEVELOPER);
    }

    @Test
    void resolvingNeverWrites() {
        // Reads only. A resolver that saved while authorising would turn every
        // authenticated request into a write.
        OrganizationMembership viewer = membership(OrganizationRole.VIEWER);
        givenOwnMembership(viewer);
        UUID workspaceId = viewer.getOrganization().getId();

        resolver.principalFor(caller, workspaceId);

        org.mockito.Mockito.verify(membershipRepository, org.mockito.Mockito.never())
                .save(any(OrganizationMembership.class));
        org.mockito.Mockito.verify(membershipRepository, org.mockito.Mockito.never())
                .delete(any(OrganizationMembership.class));
    }
}