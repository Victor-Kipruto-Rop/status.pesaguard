package com.pesaguard.backend.rbac.domain;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * An organization-scoped custom role. Permissions are stored as a canonical
 * sorted, comma-separated list; unknown values are rejected on write and cause a
 * hard failure on read rather than being silently ignored.
 */
@Entity
@Table(name = "organization_roles")
public class CustomRole {

    private static final String SEPARATOR = ",";

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "name", nullable = false, length = 64)
    private String name;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "permissions", nullable = false, columnDefinition = "text")
    private String permissions;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private CustomRoleStatus status;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected CustomRole() {
    }

    private CustomRole(UUID organizationId, String name, String description,
            Set<Permission> permissions, UUID createdBy) {
        if (permissions == null || permissions.isEmpty()) {
            throw new IllegalArgumentException("A custom role must grant at least one permission");
        }
        this.id = UUID.randomUUID();
        this.organizationId = organizationId;
        this.name = name.trim();
        this.description = description == null || description.isBlank() ? null : description.trim();
        this.permissions = encode(permissions);
        this.status = CustomRoleStatus.ACTIVE;
        this.createdBy = createdBy;
    }

    public static CustomRole create(UUID organizationId, String name, String description,
            Set<Permission> permissions, UUID createdBy) {
        return new CustomRole(organizationId, name, description, permissions, createdBy);
    }

    public void update(String name, String description, Set<Permission> newPermissions, UUID updatedBy) {
        requireActive();
        if (newPermissions == null || newPermissions.isEmpty()) {
            throw new IllegalArgumentException("A custom role must grant at least one permission");
        }
        this.name = name.trim();
        this.description = description == null || description.isBlank() ? null : description.trim();
        this.permissions = encode(newPermissions);
        this.updatedBy = updatedBy;
    }

    public void archive() {
        if (status == CustomRoleStatus.ACTIVE) {
            status = CustomRoleStatus.ARCHIVED;
        }
    }

    public void restore() {
        if (status == CustomRoleStatus.ARCHIVED) {
            status = CustomRoleStatus.ACTIVE;
        }
    }

    public Set<Permission> getPermissions() {
        Set<Permission> result = EnumSet.noneOf(Permission.class);
        for (String value : decode(permissions)) {
            result.add(Permission.parse(value).orElseThrow(() ->
                    new IllegalStateException("Stored role contains an unknown permission: " + value)));
        }
        return Collections.unmodifiableSet(result);
    }

    private void requireActive() {
        if (status == CustomRoleStatus.ARCHIVED) {
            throw new IllegalStateException("An archived role cannot be edited");
        }
    }

    private static String encode(Set<Permission> permissions) {
        return permissions.stream().map(Permission::value).sorted().collect(Collectors.joining(SEPARATOR));
    }

    private static Set<String> decode(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(value.split(SEPARATOR))
                .map(String::trim)
                .filter(part -> !part.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public CustomRoleStatus getStatus() { return status; }
    public UUID getCreatedBy() { return createdBy; }
    public UUID getUpdatedBy() { return updatedBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public boolean isActive() { return status == CustomRoleStatus.ACTIVE; }
}