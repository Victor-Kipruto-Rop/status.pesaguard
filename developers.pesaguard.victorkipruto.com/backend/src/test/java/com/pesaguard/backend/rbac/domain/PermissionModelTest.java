package com.pesaguard.backend.rbac.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.pesaguard.backend.organization.domain.OrganizationRole;

class PermissionModelTest {

    @Test
    void catalogContainsEveryAgreedPermission() {
        assertThat(Permission.allValues()).containsExactlyInAnyOrder(
                "organization:read", "organization:update",
                "project:read", "project:create", "project:update", "project:delete",
                "environment:read", "environment:create", "environment:update",
                "credential:read", "credential:create", "credential:revoke", "credential:rotate", "credential:update",
                "webhook:read", "webhook:create", "webhook:update", "webhook:delete",
                "oauth_application:read", "oauth_application:create", "oauth_application:update",
                "oauth_application:rotate", "oauth_application:suspend", "oauth_application:revoke",
                "sandbox:read", "sandbox:create", "sandbox:update", "sandbox:activate",
                "sandbox:suspend", "sandbox:reset", "sandbox:execute", "sandbox:delete",
                "usage:read",
                "audit:read",
                "security:read", "security:control",
                "workspace_settings:read", "workspace_settings:update",
                "workspace:invite", "workspace_member:remove",
                "production_access:request", "production_access:review",
                "production:view", "production:verify", "production:launch",
                "production:suspend", "production:resume");
    }

    @Test
    void unknownPermissionStringsAreRejected() {
        assertThat(Permission.parse("project:teleport")).isEmpty();
        assertThat(Permission.parse("")).isEmpty();
        assertThat(Permission.parse(null)).isEmpty();
        assertThat(Permission.parse("project:read")).contains(Permission.PROJECT_READ);
    }

    @Test
    void permissionStringsAreCaseSensitive() {
        assertThat(Permission.parse("PROJECT:READ")).isEmpty();
        assertThat(Permission.parse("project:READ")).isEmpty();
        assertThat(Permission.parse(" project:read ")).contains(Permission.PROJECT_READ);
    }

    @Test
    void builtInRolesAreLeastPrivilege() {
        assertThat(OrganizationRole.VIEWER.permissions()).doesNotContain(
                Permission.PROJECT_CREATE, Permission.CREDENTIAL_CREATE, Permission.AUDIT_READ);
        assertThat(OrganizationRole.ANALYST.permissions()).doesNotContain(
                Permission.PROJECT_UPDATE, Permission.CREDENTIAL_REVOKE, Permission.PRODUCTION_ACCESS_REVIEW);
        assertThat(OrganizationRole.DEVELOPER.permissions()).doesNotContain(
                Permission.PROJECT_DELETE, Permission.PRODUCTION_ACCESS_REVIEW, Permission.AUDIT_READ);
        assertThat(OrganizationRole.SECURITY.permissions()).doesNotContain(
                Permission.ORGANIZATION_UPDATE, Permission.PROJECT_CREATE, Permission.PROJECT_DELETE);
    }

    @Test
    void goLivePermissionsSeparateViewingVerificationAndLifecycleControl() {
        assertThat(OrganizationRole.DEVELOPER.permissions())
                .contains(Permission.PRODUCTION_VIEW, Permission.PRODUCTION_VERIFY)
                .doesNotContain(Permission.PRODUCTION_LAUNCH, Permission.PRODUCTION_SUSPEND,
                        Permission.PRODUCTION_RESUME);
        assertThat(OrganizationRole.VIEWER.permissions())
                .contains(Permission.PRODUCTION_VIEW)
                .doesNotContain(Permission.PRODUCTION_VERIFY, Permission.PRODUCTION_LAUNCH);
        assertThat(OrganizationRole.OWNER.permissions())
                .contains(Permission.PRODUCTION_VIEW, Permission.PRODUCTION_VERIFY,
                        Permission.PRODUCTION_LAUNCH, Permission.PRODUCTION_SUSPEND,
                        Permission.PRODUCTION_RESUME);
    }

    @Test
    void onlyOwnerAndAdminCanManageRoles() {
        assertThat(OrganizationRole.all())
                .filteredOn(OrganizationRole::managesRoles)
                .containsExactlyInAnyOrder(OrganizationRole.OWNER, OrganizationRole.ADMIN);
    }

    @Test
    void securityHoldsReviewAndAuditButNotRequest() {
        Set<Permission> permissions = OrganizationRole.SECURITY.permissions();

        assertThat(permissions).contains(
                Permission.PRODUCTION_ACCESS_REVIEW, Permission.AUDIT_READ, Permission.CREDENTIAL_REVOKE);
        assertThat(permissions).doesNotContain(Permission.PRODUCTION_ACCESS_REQUEST);
    }

    @Test
    void noBuiltInRoleBothRequestsAndReviewsProductionAccess() {
        assertThat(OrganizationRole.all())
                .allSatisfy(role -> assertThat(role.violatesSeparationOfDuties(role.permissions()))
                        .as("role %s must not be able to self-approve production access", role)
                        .isFalse());
    }

    @Test
    void ownerReviewsButCannotRequestProductionAccess() {
        assertThat(OrganizationRole.OWNER.permissions())
                .contains(Permission.PRODUCTION_ACCESS_REVIEW)
                .doesNotContain(Permission.PRODUCTION_ACCESS_REQUEST);
        assertThat(OrganizationRole.DEVELOPER.permissions())
                .contains(Permission.PRODUCTION_ACCESS_REQUEST)
                .doesNotContain(Permission.PRODUCTION_ACCESS_REVIEW);
    }

    @Test
    void separationOfDutiesIsDetected() {
        assertThat(OrganizationRole.OWNER.violatesSeparationOfDuties(EnumSet.of(
                Permission.PRODUCTION_ACCESS_REQUEST, Permission.PRODUCTION_ACCESS_REVIEW))).isTrue();
        assertThat(OrganizationRole.OWNER.violatesSeparationOfDuties(
                EnumSet.of(Permission.PRODUCTION_ACCESS_REQUEST))).isFalse();
    }

    @Test
    void resourceIsDerivedFromThePermissionString() {
        assertThat(Permission.CREDENTIAL_ROTATE.resource()).isEqualTo("credential");
        assertThat(Permission.PRODUCTION_ACCESS_REVIEW.resource()).isEqualTo("production_access");
        assertThat(Permission.PRODUCTION_VERIFY.resource()).isEqualTo("production");
    }
}
