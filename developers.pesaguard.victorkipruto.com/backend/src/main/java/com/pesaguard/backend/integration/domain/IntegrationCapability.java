package com.pesaguard.backend.integration.domain;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "integration_capabilities")
public class IntegrationCapability {

    @Id
    private UUID id;

    @Column(name = "integration_id", nullable = false)
    private UUID integrationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "capability", nullable = false, length = 32)
    private IntegrationCapabilityType capability;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private IntegrationCapabilityStatus status;

    @Column(name = "required_scopes", nullable = false, length = 500)
    private String requiredScopes;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "last_verified_at")
    private Instant lastVerifiedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected IntegrationCapability() {
    }

    private IntegrationCapability(UUID integrationId, IntegrationCapabilityType capability,
            Set<String> scopes) {
        this.id = UUID.randomUUID();
        this.integrationId = integrationId;
        this.capability = capability;
        this.status = IntegrationCapabilityStatus.AVAILABLE;
        this.requiredScopes = scopes.stream().sorted().collect(Collectors.joining(","));
        this.enabled = false;
    }

    public static IntegrationCapability available(UUID integrationId,
            IntegrationCapabilityType capability, Set<String> scopes) {
        return new IntegrationCapability(integrationId, capability, scopes);
    }

    public Set<String> requiredScopeSet() {
        return requiredScopes.isBlank() ? Set.of() : Set.of(requiredScopes.split(","));
    }

    public void verifyScopes(Set<String> grantedScopes, Instant now) {
        this.lastVerifiedAt = now;
        boolean permitted = grantedScopes.containsAll(requiredScopeSet());
        this.status = permitted
                ? (enabled ? IntegrationCapabilityStatus.ENABLED : IntegrationCapabilityStatus.AVAILABLE)
                : IntegrationCapabilityStatus.REQUIRES_SCOPE;
    }

    public UUID getId() { return id; }
    public UUID getIntegrationId() { return integrationId; }
    public IntegrationCapabilityType getCapability() { return capability; }
    public IntegrationCapabilityStatus getStatus() { return status; }
    public Set<String> getRequiredScopes() { return requiredScopeSet(); }
    public boolean isEnabled() { return enabled; }
    public Instant getLastVerifiedAt() { return lastVerifiedAt; }
}
