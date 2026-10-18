package com.pesaguard.backend.member.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
@Table(name = "users")
public class UserAccount {

    @Id
    private UUID id;

    @Column(name = "email", nullable = false, unique = true, length = 320)
    private String email;

    @Column(name = "username", nullable = false, length = 32)
    private String username;

    @Column(name = "phone_number", length = 16)
    private String phoneNumber;

    @Column(name = "display_name", nullable = false, length = 120)
    private String displayName;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private UserStatus status;

    /**
     * When deletion was first requested, for the grace period.
     *
     * <p>Null unless a deletion is pending. Kept separate from {@code updatedAt} so
     * an unrelated edit cannot silently extend or shorten a grace period.
     */
    @Column(name = "deletion_requested_at")
    private Instant deletionRequestedAt;

    @Column(name = "deactivated_until")
    private Instant deactivatedUntil;

    /**
     * When the address was proven to belong to this developer.
     *
     * <p>Null until verified. Distinct from {@link UserStatus}: an account can be
     * ACTIVE and still unverified, because registration must not block on an email
     * round trip that may be filtered.
     */
    @Column(name = "email_verified_at")
    private Instant emailVerifiedAt;

    /**
     * HMAC of the outstanding verification token; null once verified.
     *
     * <p>A hash, never the token. Same reasoning as a session or API-key secret: a
     * leaked table must not let an attacker verify an arbitrary address.
     */
    @Column(name = "email_verification_hash", length = 64)
    private String emailVerificationHash;

    @Column(name = "email_verification_issued_at")
    private Instant emailVerificationIssuedAt;

    @Column(name = "email_verification_attempts", nullable = false)
    private int emailVerificationAttempts;

    @Column(name = "login_mfa_challenge_id", unique = true)
    private UUID loginMfaChallengeId;

    @Column(name = "login_mfa_organization_id")
    private UUID loginMfaOrganizationId;

    @Column(name = "login_mfa_code_hash", length = 64)
    private String loginMfaCodeHash;

    @Column(name = "login_mfa_issued_at")
    private Instant loginMfaIssuedAt;

    @Column(name = "login_mfa_attempts", nullable = false)
    private int loginMfaAttempts;

    @Column(name = "password_change_challenge_id", unique = true)
    private UUID passwordChangeChallengeId;

    @Column(name = "password_change_session_id")
    private UUID passwordChangeSessionId;

    @Column(name = "password_change_code_hash", length = 64)
    private String passwordChangeCodeHash;

    @Column(name = "password_change_issued_at")
    private Instant passwordChangeIssuedAt;

    @Column(name = "password_change_attempts", nullable = false)
    private int passwordChangeAttempts;

    @Column(name = "last_accessed_workspace_id")
    private UUID lastAccessedWorkspaceId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected UserAccount() {
    }

    private UserAccount(UUID id, String email, String username, String displayName, String passwordHash,
            String phoneNumber) {
        this.id = id;
        this.email = email;
        this.username = requireUsername(username);
        this.displayName = displayName;
        this.passwordHash = passwordHash;
        this.phoneNumber = phoneNumber;
        this.status = UserStatus.ACTIVE;
    }

    public static UserAccount create(String email, String username, String displayName, String passwordHash) {
        return create(email, username, displayName, passwordHash, null);
    }

    public static UserAccount create(String email, String username, String displayName, String passwordHash,
            String phoneNumber) {
        return new UserAccount(UUID.randomUUID(), email, username, displayName, passwordHash, phoneNumber);
    }

    public static UserAccount create(String email, String displayName, String passwordHash) {
        String localPart = email.substring(0, email.indexOf('@')).toLowerCase(java.util.Locale.ROOT);
        String username = localPart.replaceAll("[^a-z0-9._-]", "")
                .replaceAll("^[._-]+|[._-]+$", "");
        if (username.length() < 3) {
            username = "developer";
        }
        return create(email, username.substring(0, Math.min(username.length(), 32)), displayName, passwordHash);
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getUsername() {
        return username;
    }

    public String getPhoneNumber() {
        return phoneNumber;
    }

    public void updateUsername(String username, Instant now) {
        this.username = requireUsername(username);
        this.updatedAt = now;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void updateDisplayName(String displayName, Instant now) {
        if (displayName == null || displayName.isBlank() || displayName.trim().length() > 120) {
            throw new IllegalArgumentException("A valid display name is required");
        }
        this.displayName = displayName.trim();
        this.updatedAt = now;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public UUID getLastAccessedWorkspaceId() {
        return lastAccessedWorkspaceId;
    }

    public void recordWorkspaceAccess(UUID workspaceId) {
        if (workspaceId == null) {
            throw new IllegalArgumentException("A workspace is required.");
        }
        lastAccessedWorkspaceId = workspaceId;
    }

    public UserStatus getStatus() {
        return status;
    }

    public boolean isActive() {
        return status == UserStatus.ACTIVE;
    }


    /**
     * Suspends the account.
     *
     * <p>Reversible. Suspension blocks authentication but deliberately does not
     * block a deletion request, so a suspended developer is not trapped.
     */
    public void suspend(Instant now) {
        if (status == UserStatus.DELETED) {
            throw new IllegalStateException("A deleted account cannot be suspended");
        }
        this.status = UserStatus.SUSPENDED;
        this.deactivatedUntil = null;
        this.updatedAt = now;
    }

    /** Restores a suspended or deactivated account to active. */
    public void restore(Instant now) {
        if (status == UserStatus.DELETED) {
            // Terminal. Reinstating would resurrect a developer who asked to be
            // forgotten, which is a privacy expectation and not a preference.
            throw new IllegalStateException("A deleted account cannot be restored");
        }
        if (status == UserStatus.PENDING_DELETION) {
            throw new IllegalStateException(
                    "Cancel the pending deletion before restoring the account");
        }
        this.status = UserStatus.ACTIVE;
        this.deactivatedUntil = null;
        this.updatedAt = now;
    }

    /**
     * Deactivates the account at the developer's own request.
     *
     * <p>Authentication credentials are revoked by the application service
     * performing this transition.
     */
    public void deactivate(Instant now) {
        deactivate(now, null);
    }

    public void deactivate(Instant now, Instant until) {
        if (status == UserStatus.DELETED) {
            throw new IllegalStateException("A deleted account cannot be deactivated");
        }
        this.status = UserStatus.DEACTIVATED;
        this.deactivatedUntil = until;
        this.emailVerificationHash = null;
        this.emailVerificationIssuedAt = null;
        this.emailVerificationAttempts = 0;
        clearPasswordChange(now);
        clearLoginMfa(now);
        this.updatedAt = now;
    }

    public Instant getDeactivatedUntil() {
        return deactivatedUntil;
    }

    public boolean isPauseDue(Instant now) {
        return status == UserStatus.DEACTIVATED && deactivatedUntil != null && !deactivatedUntil.isAfter(now);
    }

    /**
     * Removes personal profile values after the deletion grace period.
     *
     * <p>The account id and deletion timestamp remain so append-only audit and
     * shared-organization records keep a stable reference to this identity.
     */
    public void anonymizeForDeletion(String unusablePasswordHash, Instant now) {
        if (status != UserStatus.DELETED) {
            throw new IllegalStateException("Only a deleted account can be anonymized");
        }
        if (unusablePasswordHash == null || unusablePasswordHash.isBlank()) {
            throw new IllegalArgumentException("An unusable password hash is required");
        }
        String compactId = id.toString().replace("-", "");
        this.email = "deleted+" + compactId + "@deleted.invalid";
        this.username = "deleted-" + compactId.substring(0, 24);
        this.displayName = "Deleted user";
        this.passwordHash = unusablePasswordHash;
        this.emailVerifiedAt = null;
        this.emailVerificationHash = null;
        this.emailVerificationIssuedAt = null;
        this.emailVerificationAttempts = 0;
        clearPasswordChange(now);
        clearLoginMfa(now);
        this.updatedAt = now;
    }
    /** Whether the developer has proven they own this address. */
    public boolean isEmailVerified() {
        return emailVerifiedAt != null;
    }

    public Instant getEmailVerifiedAt() {
        return emailVerifiedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getEmailVerificationHash() {
        return emailVerificationHash;
    }

    public Instant getEmailVerificationIssuedAt() {
        return emailVerificationIssuedAt;
    }

    /**
     * Records a pending verification.
     *
     * <p>Re-issuing replaces the outstanding token, so a second request
     * invalidates the first. Two live tokens would leave an address the developer
     * abandoned still verifiable afterwards.
     */
    public void beginEmailVerification(String tokenHash, Instant now) {
        this.emailVerificationHash = requireHash(tokenHash);
        this.emailVerificationIssuedAt = now;
        this.emailVerificationAttempts = 0;
        this.emailVerifiedAt = null;
        this.updatedAt = now;
    }

    /** Marks the address verified and clears the outstanding token. */
    public void verifyEmail(Instant now) {
        this.emailVerifiedAt = now;
        this.emailVerificationHash = null;
        this.emailVerificationIssuedAt = null;
        this.emailVerificationAttempts = 0;
        this.updatedAt = now;
    }

    /** Whether this hash is the outstanding verification token, compared in constant time. */
    public boolean matchesEmailVerificationHash(String candidate) {
        return emailVerificationHash != null && candidate != null
                && MessageDigest.isEqual(
                        emailVerificationHash.getBytes(StandardCharsets.US_ASCII),
                        candidate.getBytes(StandardCharsets.US_ASCII));
    }

    public int recordEmailVerificationFailure() {
        return ++emailVerificationAttempts;
    }

    public int getEmailVerificationAttempts() {
        return emailVerificationAttempts;
    }

    public UUID getLoginMfaChallengeId() {
        return loginMfaChallengeId;
    }

    public UUID getLoginMfaOrganizationId() {
        return loginMfaOrganizationId;
    }

    public String getLoginMfaCodeHash() {
        return loginMfaCodeHash;
    }

    public Instant getLoginMfaIssuedAt() {
        return loginMfaIssuedAt;
    }

    public int getLoginMfaAttempts() {
        return loginMfaAttempts;
    }

    public UUID getPasswordChangeChallengeId() {
        return passwordChangeChallengeId;
    }

    public UUID getPasswordChangeSessionId() {
        return passwordChangeSessionId;
    }

    public String getPasswordChangeCodeHash() {
        return passwordChangeCodeHash;
    }

    public Instant getPasswordChangeIssuedAt() {
        return passwordChangeIssuedAt;
    }

    public int getPasswordChangeAttempts() {
        return passwordChangeAttempts;
    }

    public void issuePasswordChangeChallenge(UUID challengeId, UUID sessionId, String codeHash, Instant now) {
        if (challengeId == null || sessionId == null || codeHash == null || codeHash.isBlank()) {
            throw new IllegalArgumentException("A password change verification challenge is required.");
        }
        passwordChangeChallengeId = challengeId;
        passwordChangeSessionId = sessionId;
        passwordChangeCodeHash = codeHash;
        passwordChangeIssuedAt = now;
        passwordChangeAttempts = 0;
        updatedAt = now;
    }

    public boolean matchesPasswordChangeChallenge(UUID challengeId, UUID sessionId, String codeHash) {
        return passwordChangeChallengeId != null && passwordChangeChallengeId.equals(challengeId)
                && passwordChangeSessionId != null && passwordChangeSessionId.equals(sessionId)
                && passwordChangeCodeHash != null && codeHash != null
                && MessageDigest.isEqual(
                        passwordChangeCodeHash.getBytes(StandardCharsets.US_ASCII),
                        codeHash.getBytes(StandardCharsets.US_ASCII));
    }

    public int recordPasswordChangeFailure() {
        return ++passwordChangeAttempts;
    }

    public void clearPasswordChange(Instant now) {
        passwordChangeChallengeId = null;
        passwordChangeSessionId = null;
        passwordChangeCodeHash = null;
        passwordChangeIssuedAt = null;
        passwordChangeAttempts = 0;
        updatedAt = now;
    }

    public void issueLoginMfa(UUID challengeId, UUID organizationId, String codeHash, Instant now) {
        if (challengeId == null || organizationId == null || codeHash == null || codeHash.isBlank()) {
            throw new IllegalArgumentException("A login email verification challenge is required.");
        }
        this.loginMfaChallengeId = challengeId;
        this.loginMfaOrganizationId = organizationId;
        this.loginMfaCodeHash = codeHash;
        this.loginMfaIssuedAt = now;
        this.loginMfaAttempts = 0;
        this.updatedAt = now;
    }

    public void bindLoginMfaToWorkspace(UUID organizationId, Instant now) {
        if (loginMfaChallengeId == null || organizationId == null) {
            throw new IllegalStateException("An active login email verification challenge is required.");
        }
        loginMfaOrganizationId = organizationId;
        updatedAt = now;
    }

    public boolean matchesLoginMfa(UUID challengeId, String codeHash) {
        return loginMfaChallengeId != null && loginMfaChallengeId.equals(challengeId)
                && loginMfaCodeHash != null && codeHash != null
                && MessageDigest.isEqual(
                        loginMfaCodeHash.getBytes(StandardCharsets.US_ASCII),
                        codeHash.getBytes(StandardCharsets.US_ASCII));
    }

    public int recordLoginMfaFailure() {
        return ++loginMfaAttempts;
    }

    public void clearLoginMfa(Instant now) {
        loginMfaChallengeId = null;
        loginMfaOrganizationId = null;
        loginMfaCodeHash = null;
        loginMfaIssuedAt = null;
        loginMfaAttempts = 0;
        updatedAt = now;
    }

    public void changeVerifiedEmail(String newEmail, Instant now) {
        if (newEmail == null || newEmail.isBlank() || newEmail.trim().length() > 320
                || !newEmail.trim().matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
            throw new IllegalArgumentException("A valid email address is required.");
        }
        email = newEmail.trim().toLowerCase(java.util.Locale.ROOT);
        emailVerifiedAt = now;
        emailVerificationHash = null;
        emailVerificationIssuedAt = null;
        emailVerificationAttempts = 0;
        clearLoginMfa(now);
        updatedAt = now;
    }

    /** Clears verification, for a changed address. */
    public void clearEmailVerification(Instant now) {
        this.emailVerifiedAt = null;
        this.emailVerificationHash = null;
        this.emailVerificationIssuedAt = null;
        this.emailVerificationAttempts = 0;
        this.updatedAt = now;
    }

    private static String requireHash(String tokenHash) {
        if (tokenHash == null || tokenHash.isBlank()) {
            throw new IllegalArgumentException("A verification token hash is required");
        }
        return tokenHash.trim();
    }

    private static String requireUsername(String username) {
        if (username == null || !username.matches("(?i)[a-z0-9][a-z0-9._-]{2,31}")) {
            throw new IllegalArgumentException("A username must be 3 to 32 letters, numbers, dots, underscores, or hyphens");
        }
        return username.toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Requests removal, starting the grace period.
     *
     * <p>Reversible. The account continues to work until the grace period elapses,
     * because a deletion triggered by mistake and discovered a week later would
     * otherwise be unrecoverable.
     */
    public void requestDeletion(Instant now) {
        if (status == UserStatus.DELETED) {
            throw new IllegalStateException("This account is already deleted");
        }
        // Re-requesting while already pending is rejected rather than treated as a
        // no-op, because resetting the clock would extend the grace period. Anyone
        // who can reach this operation could then push the deletion date out
        // indefinitely and it would never complete.
        if (status == UserStatus.PENDING_DELETION) {
            throw new IllegalStateException("This account already has a pending deletion");
        }
        this.status = UserStatus.PENDING_DELETION;
        this.deletionRequestedAt = now;
        this.deactivatedUntil = null;
        clearPasswordChange(now);
        clearLoginMfa(now);
        this.updatedAt = now;
    }

    /** Cancels a deletion request during the grace period. */
    public void cancelDeletion(Instant now) {
        if (status != UserStatus.PENDING_DELETION) {
            throw new IllegalStateException("This account has no pending deletion");
        }
        this.status = UserStatus.ACTIVE;
        this.deletionRequestedAt = null;
        this.updatedAt = now;
    }

    /**
     * Completes a deletion whose grace period has elapsed.
     *
     * <p>Only transitions status. It never removes audit rows: an audit trail
     * with holes in it is worse than one referencing an account that no longer
     * exists, because a gap is indistinguishable from tampering while a dangling
     * identifier is self-evidently a reference.
     */
    public void completeDeletion(Instant now) {
        if (status != UserStatus.PENDING_DELETION) {
            throw new IllegalStateException("This account has no pending deletion");
        }
        this.status = UserStatus.DELETED;
        this.deactivatedUntil = null;
        this.updatedAt = now;
    }

    /**
     * Completes a deletion once the grace period has passed.
     *
     * @return true if it completed, false if the grace period is still running
     */
    public boolean completeDeletionIfElapsed(Instant now, java.time.Duration grace) {
        if (status != UserStatus.PENDING_DELETION || deletionRequestedAt == null) {
            return false;
        }
        if (now.isBefore(deletionRequestedAt.plus(grace))) {
            return false;
        }
        completeDeletion(now);
        return true;
    }

    /** When deletion completes, if requested. */
    public java.util.Optional<Instant> deletionCompletesAt(java.time.Duration grace) {
        if (status != UserStatus.PENDING_DELETION || deletionRequestedAt == null) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(deletionRequestedAt.plus(grace));
    }

    /**
     * Replaces the stored credential hash.
     *
     * <p>Only ever called with the output of the password encoder, never with a
     * plaintext password. A bcrypt hash is 60 characters, so the column's
     * length of 100 is deliberate headroom rather than an invitation to store
     * something unbounded.
     *
     * <p>A password change does not clear {@code emailVerifiedAt}: proving you
     * can receive mail at an address is independent of knowing the password, and
     * forcing re-verification here would let anyone who steals an account lock
     * the real owner out of their own inbox.
     */
    public void changePassword(String encodedPassword, Instant now) {
        if (encodedPassword == null || encodedPassword.isBlank()) {
            throw new IllegalArgumentException("An encoded password is required");
        }
        this.passwordHash = encodedPassword;
        clearPasswordChange(now);
        clearLoginMfa(now);
        this.updatedAt = now;
    }

    public Instant getDeletionRequestedAt() {
        return deletionRequestedAt;
    }
}
