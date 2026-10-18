package com.pesaguard.backend.oauth.domain;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
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
import jakarta.persistence.Version;

/**
 * A registered OAuth client.
 *
 * <p>Application lifecycle: REGISTERED -&gt; ACTIVE -&gt; SUSPENDED -&gt; ACTIVE,
 * with REVOKED as a terminal state. A revoked application can never authorize
 * again, and its refresh token family is revoked with it.
 *
 * <p>Only the HMAC of the client secret is stored. A short hint is kept for
 * operator recognition; the secret itself is returned exactly once, at issue or
 * rotation time, and is never readable from this entity.
 */
@Entity
@Table(name = "oauth_applications")
public class OAuthApplication {

    private static final String SEPARATOR = ",";

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "project_id")
    private UUID projectId;

    @Column(name = "environment_id")
    private UUID environmentId;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "client_id", nullable = false, unique = true, length = 64)
    private String clientId;

    @Column(name = "client_secret_hash", nullable = false, length = 64)
    private String clientSecretHash;

    @Column(name = "client_secret_hint", nullable = false, length = 12)
    private String clientSecretHint;

    @Column(name = "client_secret_version", nullable = false)
    private int clientSecretVersion;

    @Column(name = "redirect_uris", nullable = false, columnDefinition = "text")
    private String redirectUris;

    @Column(name = "allowed_origins", nullable = false, columnDefinition = "text")
    private String allowedOrigins;

    @Column(name = "scopes", nullable = false, columnDefinition = "text")
    private String scopes;

    @Column(name = "grant_types", nullable = false, columnDefinition = "text")
    private String grantTypes;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private ApplicationStatus status;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "verification_reference", length = 120)
    private String verificationReference;

    @Column(name = "status_changed_at", nullable = false)
    private Instant statusChangedAt;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected OAuthApplication() {
    }

    private OAuthApplication(UUID organizationId, String name, String description, String clientId,
            String clientSecretHash, String clientSecretHint, Set<String> redirectUris,
            Set<String> allowedOrigins, Set<String> scopes, UUID createdBy, Instant now) {
        if (redirectUris == null || redirectUris.isEmpty()) {
            throw new IllegalArgumentException("At least one redirect URI is required");
        }
        this.id = UUID.randomUUID();
        this.organizationId = organizationId;
        this.name = name.trim();
        this.description = description == null || description.isBlank() ? null : description.trim();
        this.clientId = clientId;
        this.clientSecretHash = clientSecretHash;
        this.clientSecretHint = clientSecretHint;
        this.clientSecretVersion = 1;
        this.redirectUris = encode(redirectUris);
        this.allowedOrigins = encode(allowedOrigins);
        this.scopes = encode(scopes);
        this.grantTypes = "authorization_code,refresh_token";
        this.status = ApplicationStatus.REGISTERED;
        this.statusChangedAt = now;
        this.createdBy = createdBy;
    }

    public static OAuthApplication register(UUID organizationId, String name, String description,
            String clientId, String clientSecretHash, String clientSecretHint, Set<String> redirectUris,
            Set<String> allowedOrigins, Set<String> scopes, UUID createdBy, Instant now) {
        return new OAuthApplication(organizationId, name, description, clientId, clientSecretHash,
                clientSecretHint, redirectUris, allowedOrigins, scopes, createdBy, now);
    }

    public void bindTo(UUID projectId, UUID environmentId) {
        this.projectId = projectId;
        this.environmentId = environmentId;
    }

    public void update(String name, String description, Set<String> redirectUris,
            Set<String> allowedOrigins, Set<String> scopes) {
        requireNotRevoked();
        if (redirectUris == null || redirectUris.isEmpty()) {
            throw new IllegalArgumentException("At least one redirect URI is required");
        }
        this.name = name.trim();
        this.description = description == null || description.isBlank() ? null : description.trim();
        this.redirectUris = encode(redirectUris);
        this.allowedOrigins = encode(allowedOrigins);
        this.scopes = encode(scopes);
    }

    public void rotateSecret(String secretHash, String hint) {
        requireNotRevoked();
        this.clientSecretHash = secretHash;
        this.clientSecretHint = hint;
        this.clientSecretVersion++;
    }

    public void verify(String reference, Instant now) {
        requireNotRevoked();
        this.status = ApplicationStatus.ACTIVE;
        this.verifiedAt = now;
        this.verificationReference = reference;
        this.statusChangedAt = now;
    }

    public void suspend(Instant now) {
        requireNotRevoked();
        if (status != ApplicationStatus.ACTIVE) {
            throw new IllegalStateException("Only an active application can be suspended");
        }
        this.status = ApplicationStatus.SUSPENDED;
        this.statusChangedAt = now;
    }

    public void resume(Instant now) {
        requireNotRevoked();
        if (status != ApplicationStatus.SUSPENDED) {
            throw new IllegalStateException("Only a suspended application can be resumed");
        }
        this.status = ApplicationStatus.ACTIVE;
        this.statusChangedAt = now;
    }

    public void revoke(Instant now) {
        if (status == ApplicationStatus.REVOKED) {
            return;
        }
        this.status = ApplicationStatus.REVOKED;
        this.revokedAt = now;
        this.statusChangedAt = now;
    }

    /** Exact-match redirect URI lookup; never a prefix or wildcard match. */
    public boolean isRedirectUriAllowed(String presented) {
        return decode(redirectUris).stream()
                .anyMatch(registered -> RedirectUriPolicy.matches(registered, presented));
    }

    public boolean isScopeAllowed(String requested) {
        return requested == null || requested.isBlank() || decode(scopes).contains(requested.trim());
    }

    private void requireNotRevoked() {
        if (status.isTerminal()) {
            throw new IllegalStateException("A revoked application cannot be changed");
        }
    }

    public Set<String> redirectUriSet() { return Collections.unmodifiableSet(decode(redirectUris)); }
    public Set<String> allowedOriginSet() { return Collections.unmodifiableSet(decode(allowedOrigins)); }
    public Set<String> scopeSet() { return Collections.unmodifiableSet(decode(scopes)); }

    private static String encode(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        return values.stream().map(String::trim).filter(value -> !value.isEmpty()).sorted()
                .collect(Collectors.joining(SEPARATOR));
    }

    private static Set<String> decode(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(value.split(SEPARATOR))
                .map(String::trim)
                .filter(part -> !part.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public UUID getProjectId() { return projectId; }
    public UUID getEnvironmentId() { return environmentId; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getClientId() { return clientId; }
    public String getClientSecretHash() { return clientSecretHash; }
    public String getClientSecretHint() { return clientSecretHint; }
    public int getClientSecretVersion() { return clientSecretVersion; }
    public ApplicationStatus getStatus() { return status; }
    public Instant getVerifiedAt() { return verifiedAt; }
    public String getVerificationReference() { return verificationReference; }
    public Instant getStatusChangedAt() { return statusChangedAt; }
    public UUID getCreatedBy() { return createdBy; }
    public Instant getRevokedAt() { return revokedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}