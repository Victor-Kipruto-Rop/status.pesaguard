package com.pesaguard.backend.environment.domain;

import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashSet;
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

/**
 * Grants a named set of permissions to an organization role or a project-member
 * role, optionally restricted to an IP allowlist. Policies are per environment,
 * so a VIEWER granted write access in DEVELOPMENT gains nothing in PRODUCTION.
 */
@Entity
@Table(name = "environment_access_policies")
public class EnvironmentAccessPolicy {

    private static final String SEPARATOR = ",";

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "environment_id", nullable = false)
    private UUID environmentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "subject_type", nullable = false, length = 24)
    private EnvironmentAccessSubjectType subjectType;

    @Column(name = "subject_role", nullable = false, length = 24)
    private String subjectRole;

    @Column(name = "permissions", nullable = false, columnDefinition = "text")
    private String permissions;

    @Column(name = "ip_allowlist", columnDefinition = "text")
    private String ipAllowlist;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected EnvironmentAccessPolicy() {
    }

    private EnvironmentAccessPolicy(UUID organizationId, UUID projectId, UUID environmentId,
            EnvironmentAccessSubjectType subjectType, String subjectRole,
            Set<EnvironmentPermission> permissions, Set<String> ipAllowlist, UUID createdBy) {
        if (permissions == null || permissions.isEmpty()) {
            throw new IllegalArgumentException("At least one permission is required");
        }
        this.id = UUID.randomUUID();
        this.organizationId = organizationId;
        this.projectId = projectId;
        this.environmentId = environmentId;
        this.subjectType = subjectType;
        this.subjectRole = subjectRole;
        this.permissions = encode(permissions);
        this.ipAllowlist = encode(ipAllowlist);
        this.createdBy = createdBy;
    }

    public static EnvironmentAccessPolicy create(UUID organizationId, UUID projectId, UUID environmentId,
            EnvironmentAccessSubjectType subjectType, String subjectRole,
            Set<EnvironmentPermission> permissions, Set<String> ipAllowlist, UUID createdBy) {
        return new EnvironmentAccessPolicy(organizationId, projectId, environmentId,
                subjectType, subjectRole, permissions, ipAllowlist, createdBy);
    }

    public void grant(Set<EnvironmentPermission> newPermissions, Set<String> newIpAllowlist) {
        if (newPermissions == null || newPermissions.isEmpty()) {
            throw new IllegalArgumentException("At least one permission is required");
        }
        this.permissions = encode(newPermissions);
        this.ipAllowlist = encode(newIpAllowlist);
    }

    public boolean grants(EnvironmentPermission permission) {
        return decodePermissions().contains(permission);
    }

    public Set<EnvironmentPermission> getPermissions() {
        return decodePermissions();
    }

    public Set<String> getIpAllowlist() {
        return decodeValues(ipAllowlist);
    }

    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public UUID getProjectId() { return projectId; }
    public UUID getEnvironmentId() { return environmentId; }
    public EnvironmentAccessSubjectType getSubjectType() { return subjectType; }
    public String getSubjectRole() { return subjectRole; }
    public UUID getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    private Set<EnvironmentPermission> decodePermissions() {
        Set<EnvironmentPermission> result = EnumSet.noneOf(EnvironmentPermission.class);
        for (String value : decodeValues(permissions)) {
            try {
                result.add(EnvironmentPermission.valueOf(value));
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException("Stored policy contains an unknown permission: " + value);
            }
        }
        return result;
    }

    private static String encode(Set<?> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        return values.stream()
                .map(value -> value instanceof Enum<?> constant ? constant.name() : String.valueOf(value))
                .sorted()
                .collect(Collectors.joining(SEPARATOR));
    }

    private static Set<String> decodeValues(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(value.split(SEPARATOR))
                .map(String::trim)
                .filter(part -> !part.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}