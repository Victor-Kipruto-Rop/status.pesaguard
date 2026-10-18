package com.pesaguard.backend.credentials.api;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Encrypted one-time response retained briefly so an authorized retry is safe. */
@Entity
@Table(name = "api_key_creation_idempotency")
public class ApiKeyCreationIdempotency {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "idempotency_key_hash", nullable = false, length = 64)
    private String idempotencyKeyHash;

    @Column(name = "request_fingerprint", nullable = false, length = 64)
    private String requestFingerprint;

    @Column(name = "api_key_id", nullable = false)
    private UUID apiKeyId;

    @Column(name = "api_key_name", nullable = false, length = 120)
    private String apiKeyName;

    @Column(name = "key_prefix", nullable = false, length = 32)
    private String keyPrefix;

    @Column(name = "secret_ciphertext", nullable = false, columnDefinition = "text")
    private String secretCiphertext;

    @Column(name = "scopes", nullable = false, columnDefinition = "text")
    private String scopes;

    @Column(name = "credential_expires_at")
    private Instant credentialExpiresAt;

    @Column(name = "base_url", nullable = false, length = 512)
    private String baseUrl;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ApiKeyCreationIdempotency() {
    }

    public ApiKeyCreationIdempotency(UUID organizationId, UUID userId,
            String idempotencyKeyHash, String requestFingerprint, ApiKey key,
            String encryptedSecret, Instant expiresAt, String baseUrl) {
        this.id = UUID.randomUUID();
        this.organizationId = organizationId;
        this.userId = userId;
        this.idempotencyKeyHash = idempotencyKeyHash;
        replace(requestFingerprint, key, encryptedSecret, expiresAt, baseUrl);
    }

    public boolean isUsable(String fingerprint, Instant now) {
        return expiresAt.isAfter(now) && requestFingerprint.equals(fingerprint);
    }

    public boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }

    public void replace(String fingerprint, ApiKey key, String encryptedSecret, Instant newExpiry,
            String baseUrl) {
        requestFingerprint = fingerprint;
        apiKeyId = key.getId();
        apiKeyName = key.getName();
        keyPrefix = key.getKeyPrefix();
        secretCiphertext = encryptedSecret;
        scopes = ScopeCodec.encode(key.scopeSet());
        credentialExpiresAt = key.getExpiresAt();
        this.baseUrl = baseUrl;
        expiresAt = newExpiry;
    }

    public CreatedApiKeyView replay(com.pesaguard.backend.security.credentials.SecretEncryptionService encryption) {
        return new CreatedApiKeyView(apiKeyId, apiKeyName,
                encryption.decrypt(secretCiphertext), keyPrefix, ScopeCodec.decode(scopes), credentialExpiresAt,
                baseUrl);
    }

    public String getRequestFingerprint() { return requestFingerprint; }
    public UUID getApiKeyId() { return apiKeyId; }
    public Instant getExpiresAt() { return expiresAt; }
}
