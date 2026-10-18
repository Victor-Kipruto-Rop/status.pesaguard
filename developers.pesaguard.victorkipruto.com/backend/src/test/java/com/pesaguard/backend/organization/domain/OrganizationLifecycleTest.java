package com.pesaguard.backend.organization.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class OrganizationLifecycleTest {

    private final UUID ownerUserId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-01-01T00:00:00Z");

    private Organization organization() {
        return Organization.create("Acme", "acme", ownerUserId, now);
    }

    @Test
    void newOrganizationIsActiveDeveloperOwned() {
        Organization organization = organization();

        assertThat(organization.getStatus()).isEqualTo(OrganizationStatus.ACTIVE);
        assertThat(organization.getOrganizationType()).isEqualTo(OrganizationType.DEVELOPER);
        assertThat(organization.getOwnerUserId()).isEqualTo(ownerUserId);
        assertThat(organization.getDeletedAt()).isNull();
        assertThat(organization.isActive()).isTrue();
        assertThat(organization.getStatusChangedAt()).isEqualTo(now);
    }

    @Test
    void suspendRestoreDisableFollowTheLifecycle() {
        Organization organization = organization();

        organization.suspend(now.plusSeconds(60));
        assertThat(organization.getStatus()).isEqualTo(OrganizationStatus.SUSPENDED);
        assertThat(organization.isActive()).isFalse();

        organization.restore(now.plusSeconds(120));
        assertThat(organization.getStatus()).isEqualTo(OrganizationStatus.ACTIVE);

        organization.disable(now.plusSeconds(180));
        assertThat(organization.getStatus()).isEqualTo(OrganizationStatus.DISABLED);
        assertThat(organization.getStatusChangedAt()).isEqualTo(now.plusSeconds(180));
    }

    @Test
    void deletionIsSoftAndRepeatable() {
        Organization organization = organization();

        organization.delete(now.plusSeconds(60));
        assertThat(organization.getStatus()).isEqualTo(OrganizationStatus.DELETED);
        assertThat(organization.getDeletedAt()).isEqualTo(now.plusSeconds(60));
        assertThat(organization.isDeleted()).isTrue();

        organization.delete(now.plusSeconds(120));
        assertThat(organization.getDeletedAt()).isEqualTo(now.plusSeconds(60));
    }

    @Test
    void deletedOrganizationCannotChangeLifecycle() {
        Organization organization = organization();
        organization.delete(now);

        assertThatThrownBy(() -> organization.suspend(now.plusSeconds(60)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> organization.disable(now.plusSeconds(60)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> organization.restore(now.plusSeconds(60)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(organization.getStatus()).isEqualTo(OrganizationStatus.DELETED);
    }

    @Test
    void verificationStoresReferenceAndTimestamp() {
        Organization organization = organization();

        organization.verify("verification-123", now.plusSeconds(60));

        assertThat(organization.getVerificationReference()).isEqualTo("verification-123");
        assertThat(organization.getVerifiedAt()).isEqualTo(now.plusSeconds(60));
        assertThat(organization.getStatus()).isEqualTo(OrganizationStatus.ACTIVE);
    }

    @Test
    void metadataIsCopiedAndDefensivelyImmutable() {
        Organization organization = organization();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("region", "east-africa");

        organization.update("Acme Kenya", OrganizationType.ENTERPRISE, metadata);
        metadata.put("region", "west-africa");

        assertThat(organization.getName()).isEqualTo("Acme Kenya");
        assertThat(organization.getOrganizationType()).isEqualTo(OrganizationType.ENTERPRISE);
        assertThat(organization.getMetadata()).containsEntry("region", "east-africa");
        assertThatThrownBy(() -> organization.getMetadata().put("injected", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void ownershipTransferUpdatesTheRecordedOwner() {
        Organization organization = organization();
        UUID newOwner = UUID.randomUUID();

        organization.transferOwnership(newOwner);

        assertThat(organization.getOwnerUserId()).isEqualTo(newOwner);
    }

    @Test
    void auditSequenceIsMonotonic() {
        Organization organization = organization();

        assertThat(organization.nextAuditSequence()).isEqualTo(1);
        assertThat(organization.nextAuditSequence()).isEqualTo(2);
        assertThat(organization.nextAuditSequence()).isEqualTo(3);
    }
}