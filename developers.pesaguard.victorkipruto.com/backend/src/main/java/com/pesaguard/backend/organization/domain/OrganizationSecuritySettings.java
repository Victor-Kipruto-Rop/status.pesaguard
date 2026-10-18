package com.pesaguard.backend.organization.domain;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "organization_security_settings")
public class OrganizationSecuritySettings {

    @Id
    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "allowed_auth_methods", nullable = false, length = 512)
    private String allowedAuthMethods;

    @Column(name = "session_ttl_minutes", nullable = false)
    private int sessionTtlMinutes;

    @Column(name = "idle_timeout_minutes", nullable = false)
    private int idleTimeoutMinutes;

    @Column(name = "max_sessions", nullable = false)
    private int maxSessions;

    @Column(name = "credential_min_length", nullable = false)
    private int credentialMinLength;

    @Column(name = "credential_max_length", nullable = false)
    private int credentialMaxLength;

    @Column(name = "mfa_required", nullable = false)
    private boolean mfaRequired;

    @Column(name = "mfa_required_for_admins", nullable = false)
    private boolean mfaRequiredForAdmins;

    @Column(name = "ip_allowlist", nullable = false, columnDefinition = "text")
    private String ipAllowlist;

    @Column(name = "security_event_types", nullable = false, columnDefinition = "text")
    private String securityEventTypes;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @jakarta.persistence.Version
    @Column(name = "version", nullable = false)
    private long version;

    protected OrganizationSecuritySettings() {
    }

    private OrganizationSecuritySettings(UUID organizationId, Instant now) {
        this.organizationId = organizationId;
        this.allowedAuthMethods = "PASSWORD";
        this.sessionTtlMinutes = 480;
        this.idleTimeoutMinutes = 120;
        this.maxSessions = 10;
        this.credentialMinLength = 12;
        this.credentialMaxLength = 72;
        this.mfaRequired = false;
        this.mfaRequiredForAdmins = false;
        this.ipAllowlist = "";
        this.securityEventTypes = "LOGIN_FAILURE,MEMBERSHIP_CHANGED,SECURITY_SETTING_CHANGED";
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static OrganizationSecuritySettings defaults(UUID organizationId, Instant now) {
        return new OrganizationSecuritySettings(organizationId, now);
    }

    public void update(
            String allowedAuthMethods,
            int sessionTtlMinutes,
            int idleTimeoutMinutes,
            int maxSessions,
            int credentialMinLength,
            int credentialMaxLength,
            boolean mfaRequired,
            boolean mfaRequiredForAdmins,
            String ipAllowlist,
            String securityEventTypes,
            UUID updatedBy,
            Instant now) {
        this.allowedAuthMethods = allowedAuthMethods;
        this.sessionTtlMinutes = sessionTtlMinutes;
        this.idleTimeoutMinutes = idleTimeoutMinutes;
        this.maxSessions = maxSessions;
        this.credentialMinLength = credentialMinLength;
        this.credentialMaxLength = credentialMaxLength;
        this.mfaRequired = mfaRequired;
        this.mfaRequiredForAdmins = mfaRequiredForAdmins;
        this.ipAllowlist = ipAllowlist;
        this.securityEventTypes = securityEventTypes;
        this.updatedBy = updatedBy;
        this.updatedAt = now;
    }

    public void update(
            String allowedAuthMethods,
            int sessionTtlMinutes,
            int idleTimeoutMinutes,
            int maxSessions,
            int credentialMinLength,
            int credentialMaxLength,
            boolean mfaRequired,
            String ipAllowlist,
            String securityEventTypes,
            UUID updatedBy,
            Instant now) {
        update(allowedAuthMethods, sessionTtlMinutes, idleTimeoutMinutes, maxSessions,
                credentialMinLength, credentialMaxLength, mfaRequired, false,
                ipAllowlist, securityEventTypes, updatedBy, now);
    }

    public UUID getOrganizationId() { return organizationId; }
    public String getAllowedAuthMethods() { return allowedAuthMethods; }
    public int getSessionTtlMinutes() { return sessionTtlMinutes; }
    public int getIdleTimeoutMinutes() { return idleTimeoutMinutes; }
    public int getMaxSessions() { return maxSessions; }
    public int getCredentialMinLength() { return credentialMinLength; }
    public int getCredentialMaxLength() { return credentialMaxLength; }
    public boolean isMfaRequired() { return mfaRequired; }
    public boolean isMfaRequiredForAdmins() { return mfaRequiredForAdmins; }
    public String getIpAllowlist() { return ipAllowlist; }
    public String getSecurityEventTypes() { return securityEventTypes; }
    public UUID getUpdatedBy() { return updatedBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}