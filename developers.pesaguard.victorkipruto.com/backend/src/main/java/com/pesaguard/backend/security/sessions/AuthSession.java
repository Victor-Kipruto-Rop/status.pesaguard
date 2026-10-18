package com.pesaguard.backend.security.sessions;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import com.pesaguard.backend.organization.domain.OrganizationMembership;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "auth_sessions")
public class AuthSession {

    @Id
    private UUID id;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "membership_id", nullable = false)
    private OrganizationMembership membership;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    /**
     * Coarse device label, e.g. "Chrome on Windows".
     *
     * <p>Derived from the user agent, never stored raw: the header is
     * attacker-controlled and can contain markup, and this lands in a security
     * surface. The columns were created in V15; the entity is what finally
     * reads them.
     */
    @Column(name = "device_label", length = 64)
    private String deviceLabel;

    /** Address the session was last seen from, for "was this you?" review. */
    @Column(name = "last_ip", length = 45)
    private String lastIp;

    /**
     * The refresh family this session was issued alongside.
     *
     * <p>Records the pairing so that ending the session also ends the family.
     * Without it, signing out would kill only the access token and leave the
     * refresh token live: the client would appear logged out while still holding
     * a credential that mints a new session on the next refresh. That is a
     * logout that does not log out.
     *
     * <p>Nullable because sessions predate refresh tokens, and a session created
     * before this column existed has no family to revoke.
     */
    @Column(name = "refresh_family_id")
    private UUID refreshFamilyId;

    /**
     * Records which refresh family backs this session.
     *
     * <p>Called once, immediately after the session and its family are both
     * created, because the two ids are not both known at construction time.
     */
    public void linkRefreshFamily(UUID familyId) {
        this.refreshFamilyId = familyId;
    }

    public UUID getRefreshFamilyId() { return refreshFamilyId; }


    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AuthSession() {
    }

    private AuthSession(UUID id, String tokenHash, OrganizationMembership membership, Instant now, Instant expiresAt) {
        this.id = id;
        this.tokenHash = tokenHash;
        this.membership = membership;
        this.expiresAt = expiresAt;
        this.lastSeenAt = now;
    }


    public static AuthSession create(String tokenHash, OrganizationMembership membership, Instant now,
            Instant expiresAt) {
        return create(tokenHash, membership, now, expiresAt, null, null);
    }

    /**
     * Creates a session recording where it came from.
     *
     * <p>Recording the device at creation rather than only on the first request means
     * a session that is created and never used is still attributable, which is the
     * case that matters when investigating a lockout.
     */
    public static AuthSession create(String tokenHash, OrganizationMembership membership,
            Instant now, Instant expiresAt, String deviceLabel, String lastIp) {
        AuthSession session = new AuthSession(UUID.randomUUID(), tokenHash, membership, now,
                expiresAt);
        session.deviceLabel = truncate(deviceLabel, 64);
        // An address is sensitive personal data; it is stored to answer "was this
        // you?" and must not be padded beyond a real address length.
        session.lastIp = truncate(lastIp, 45);
        return session;
    }

    /**
     * Records activity on this session.
     *
     * <p>The device label is only overwritten when it changes, so a session that
     * reports a different agent on every request (some browsers do) does not
     * churn the column and lose the original device identity.
     */
    public void observe(Instant now, String deviceLabel, String lastIp) {
        this.lastSeenAt = now;
        if (deviceLabel != null && !deviceLabel.isBlank() && !deviceLabel.equals(this.deviceLabel)) {
            this.deviceLabel = truncate(deviceLabel, 64);
        }
        if (lastIp != null && !lastIp.isBlank()) {
            this.lastIp = truncate(lastIp, 45);
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null
                : (trimmed.length() <= max ? trimmed : trimmed.substring(0, max));
    }

    public String getDeviceLabel() { return deviceLabel; }
    public String getLastIp() { return lastIp; }


    public boolean isActive(Instant now) {
        return isActive(now, null);
    }

    public boolean isActive(Instant now, java.time.Duration idleTimeout) {
        boolean withinIdleWindow = idleTimeout == null
                || lastSeenAt.plus(idleTimeout).isAfter(now);
        return revokedAt == null && expiresAt.isAfter(now) && withinIdleWindow
                && membership.isActive() && membership.getOrganization().isActive();
    }

    public void touch(Instant now) {
        lastSeenAt = now;
    }

    public void revoke(Instant now) {
        if (revokedAt == null) {
            revokedAt = now;
        }
    }

    public UUID getId() {
        return id;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public OrganizationMembership getMembership() {
        return membership;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }
}
