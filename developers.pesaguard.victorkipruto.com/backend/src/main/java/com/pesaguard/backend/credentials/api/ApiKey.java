package com.pesaguard.backend.credentials.api;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "api_keys")
public class ApiKey {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "environment_id", nullable = false)
    private UUID environmentId;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "key_prefix", nullable = false, unique = true, length = 32)
    private String keyPrefix;

    @Column(name = "secret_hash", nullable = false, unique = true, length = 64)
    private String secretHash;

    @Column(name = "encrypted_secret")
    private String encryptedSecret;

    @Column(name = "scopes", nullable = false, columnDefinition = "text")
    private String scopes;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private ApiKeyStatus status;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "rotated_from_id")
    private UUID rotatedFromId;

    @Column(name = "ip_allowlist", columnDefinition = "text")
    private String ipAllowlist;

    @Column(name = "request_count", nullable = false)
    private long requestCount;

    @Column(name = "last_used_ip", length = 45)
    private String lastUsedIp;

    @Column(name = "last_used_country", length = 2)
    private String lastUsedCountry;

    @Column(name = "last_used_device", length = 16)
    private String lastUsedDevice;

    @Column(name = "suspended_at")
    private Instant suspendedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ApiKey() {
    }

    private ApiKey(UUID id, UUID organizationId, UUID projectId, UUID environmentId,
            String name, String keyPrefix, String secretHash, String scopes,
            Instant expiresAt, UUID createdBy) {
        this.id = id;
        this.organizationId = organizationId;
        this.projectId = projectId;
        this.environmentId = environmentId;
        this.name = name;
        this.keyPrefix = keyPrefix;
        this.secretHash = secretHash;
        this.scopes = scopes;
        this.expiresAt = expiresAt;
        this.createdBy = createdBy;
        this.status = ApiKeyStatus.CREATED;
    }

    public static ApiKey create(UUID organizationId, UUID projectId, UUID environmentId,
            String name, String keyPrefix, String secretHash, String scopes,
            Instant expiresAt, UUID createdBy) {
        return new ApiKey(UUID.randomUUID(), organizationId, projectId, environmentId,
                name, keyPrefix, secretHash, scopes, expiresAt, createdBy);
    }

    /** CREATED -&gt; ACTIVE. Performed in the same transaction as issuance. */
    public void activate() {
        if (status != ApiKeyStatus.CREATED) {
            throw new IllegalStateException("Only a newly created key can be activated");
        }
        status = ApiKeyStatus.ACTIVE;
    }

    /** ACTIVE -&gt; SUSPENDED. */
    public void suspend(Instant now) {
        if (status.isTerminal()) {
            throw new IllegalStateException("A " + status.name().toLowerCase(java.util.Locale.ROOT)
                    + " key cannot change state");
        }
        if (status != ApiKeyStatus.ACTIVE) {
            throw new IllegalStateException("Only an active key can be suspended");
        }
        status = ApiKeyStatus.SUSPENDED;
        suspendedAt = now;
    }

    /** SUSPENDED -&gt; ACTIVE. */
    public void resume() {
        if (status != ApiKeyStatus.SUSPENDED) {
            throw new IllegalStateException("Only a suspended key can be resumed");
        }
        status = ApiKeyStatus.ACTIVE;
        suspendedAt = null;
    }

    public void revoke(Instant now) {
        if (status == ApiKeyStatus.REVOKED || status == ApiKeyStatus.COMPROMISED) {
            return;
        }
        if (status == ApiKeyStatus.EXPIRED) {
            throw new IllegalStateException("An expired key cannot be revoked");
        }
        status = ApiKeyStatus.REVOKED;
        revokedAt = now;
        suspendedAt = null;
        encryptedSecret = null;
    }

    public void markCompromised(Instant now) {
        if (status == ApiKeyStatus.EXPIRED) {
            throw new IllegalStateException("An expired key cannot be marked compromised");
        }
        if (status != ApiKeyStatus.COMPROMISED) {
            status = ApiKeyStatus.COMPROMISED;
            revokedAt = now;
            suspendedAt = null;
            encryptedSecret = null;
        }
    }

    public void markExpired(Instant now) {
        if (status == ApiKeyStatus.REVOKED || status == ApiKeyStatus.EXPIRED) {
            return;
        }
        status = ApiKeyStatus.EXPIRED;
        encryptedSecret = null;
    }

    /**
     * Records a successful authentication. Only timestamps, a counter, and the
     * observed address are stored - never the presented secret.
     */
    public void recordUsage(Instant now, String remoteAddress) {
        recordUsage(now, remoteAddress, null, null);
    }

    /**
     * Stores only coarse activity metadata. The country must come from a trusted
     * edge adapter that verifies the connecting proxy; never pass an arbitrary
     * client-supplied country header here.
     */
    public void recordUsage(Instant now, String remoteAddress, String userAgent, String trustedCountryCode) {
        lastUsedAt = now;
        lastUsedIp = truncate(remoteAddress);
        requestCount++;
        lastUsedDevice = deviceFamily(userAgent);
        lastUsedCountry = trustedCountryCode != null && trustedCountryCode.matches("[A-Z]{2}")
                && !trustedCountryCode.equals("XX") ? trustedCountryCode : null;
    }

    static String deviceFamily(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) return null;
        String value = userAgent.toLowerCase(java.util.Locale.ROOT);
        if (value.contains("bot") || value.contains("crawler") || value.contains("spider")) return "Bot";
        if (value.contains("ipad") || value.contains("tablet")) return "Tablet";
        if (value.contains("mobile") || value.contains("android") || value.contains("iphone")) return "Mobile";
        return "Desktop";
    }

    /**
     * Rotation lineage. The referenced key must already exist.
     */
    public void markRotatedFrom(UUID previousKeyId) {
        this.rotatedFromId = previousKeyId;
    }

    public void restrictToIps(java.util.Set<String> cidrs) {
        this.ipAllowlist = cidrs == null || cidrs.isEmpty() ? null
                : cidrs.stream().sorted().collect(java.util.stream.Collectors.joining(","));
    }

    public void updateScopes(String encodedScopes) {
        this.scopes = encodedScopes;
    }

    public void rename(String name) {
        this.name = name;
    }

    public void storeEncryptedSecret(String encryptedSecret) {
        if (encryptedSecret == null || encryptedSecret.isBlank()) {
            throw new IllegalArgumentException("An encrypted API-key secret is required");
        }
        this.encryptedSecret = encryptedSecret;
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 45 ? value : value.substring(0, 45);
    }

    public boolean isUsable(Instant now) {
        return status.canAuthenticate() && (expiresAt == null || expiresAt.isAfter(now));
    }

    public ApiKeyStatus effectiveStatus(Instant now) {
        if (status == ApiKeyStatus.REVOKED) {
            return ApiKeyStatus.REVOKED;
        }
        if (expiresAt != null && !expiresAt.isAfter(now) && !status.isTerminal()) {
            return ApiKeyStatus.EXPIRED;
        }
        return status;
    }

    /** Scopes granted to this key. Never includes the secret itself. */
    public java.util.Set<String> scopeSet() {
        return ScopeCodec.decode(scopes);
    }

    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public UUID getProjectId() { return projectId; }
    public UUID getEnvironmentId() { return environmentId; }
    public String getName() { return name; }
    public String getKeyPrefix() { return keyPrefix; }
    public String getSecretHash() { return secretHash; }
    public String getEncryptedSecret() { return encryptedSecret; }
    public String getScopes() { return scopes; }
    public ApiKeyStatus getStatus() { return status; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public Instant getLastUsedAt() { return lastUsedAt; }
    public UUID getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public long getVersion() { return version; }
    public UUID getRotatedFromId() { return rotatedFromId; }
    public String getIpAllowlist() { return ipAllowlist; }
    public long getRequestCount() { return requestCount; }
    public String getLastUsedIp() { return lastUsedIp; }
    public String getLastUsedCountry() { return lastUsedCountry; }
    public String getLastUsedDevice() { return lastUsedDevice; }
    public Instant getSuspendedAt() { return suspendedAt; }
}
