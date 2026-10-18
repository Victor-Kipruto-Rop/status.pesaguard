package com.pesaguard.backend.scopes.domain;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * One row of the scope registry.
 *
 * <p>The registry is seeded by migration V7 and owned by code. An operator may
 * annotate a scope — deprecate it, restrict it further, record why — but may not
 * invent one. That asymmetry is intentional: a scope typo in a client
 * integration should fail loudly at assignment time, not quietly create a scope
 * that grants access to nothing and looks like it works.
 */
@Entity
@Table(name = "api_scopes")
public class ApiScopeDefinition {

    @Id
    @Column(name = "name", nullable = false, length = 64)
    private String name;

    @Column(name = "description", nullable = false, length = 500)
    private String description;

    @Column(name = "category", nullable = false, length = 32)
    private String category;

    @Column(name = "resource", nullable = false, length = 48)
    private String resource;

    @Column(name = "action", nullable = false, length = 24)
    private String action;

    @Column(name = "version", nullable = false)
    private int version;

    @Column(name = "restricted", nullable = false)
    private boolean restricted;

    @Column(name = "api_key_assignable", nullable = false)
    private boolean apiKeyAssignable;

    @Column(name = "deprecated", nullable = false)
    private boolean deprecated;

    @Column(name = "replaced_by", length = 64)
    private String replacedBy;

    @Column(name = "change_reason", length = 500)
    private String changeReason;

    @Version
    @Column(name = "scope_version_lock", nullable = false)
    private long versionLock;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ApiScopeDefinition() {
    }

    ApiScopeDefinition(String name, String description, String category, String resource,
            String action, int version, boolean restricted) {
        this.name = name;
        this.description = description;
        this.category = category;
        this.resource = resource;
        this.action = action;
        this.version = version;
        this.restricted = restricted;
        this.deprecated = false;
    }

    public ApiScope scope() {
        return new ApiScope(resource, action);
    }

    /**
     * Deprecates in favour of a replacement. The scope keeps working: removing it
     * would break every integration still using it. Clients are told to migrate
     * rather than being cut off.
     */
    public void deprecate(String replacement, String reason) {
        if (replacement != null && replacement.equals(name)) {
            throw new IllegalArgumentException("A scope cannot replace itself");
        }
        this.deprecated = true;
        this.replacedBy = replacement == null ? null : replacement.trim();
        this.changeReason = reason == null || reason.isBlank() ? null : reason.trim();
    }

    public void restrict(String reason) {
        this.restricted = true;
        this.changeReason = reason == null || reason.isBlank() ? null : reason.trim();
    }

    /**
     * Bumps the version. Callers that care about semantic change should record the
     * version on assignment so an old grant is distinguishable from a new one.
     */
    public void bumpVersion(String reason) {
        this.version++;
        this.changeReason = reason == null || reason.isBlank() ? null : reason.trim();
    }

    /** Deprecated scopes still authenticate; the caller is expected to surface this. */
    public boolean isUsable() {
        return true;
    }

    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getCategory() { return category; }
    public String getResource() { return resource; }
    public String getAction() { return action; }
    public int getVersion() { return version; }
    public boolean isRestricted() { return restricted; }
    public boolean isApiKeyAssignable() { return apiKeyAssignable; }
    public boolean isDeprecated() { return deprecated; }
    public String getReplacedBy() { return replacedBy; }
    public String getChangeReason() { return changeReason; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}