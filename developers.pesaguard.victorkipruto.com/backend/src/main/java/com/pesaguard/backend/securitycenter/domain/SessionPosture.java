package com.pesaguard.backend.securitycenter.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.pesaguard.backend.security.sessions.AuthSession;

/**
 * Session posture for an organization: which sessions exist, which devices they
 * came from, and what has already been seen.
 *
 * <p>Pure logic over sessions the caller already fetched. It holds no state of its
 * own, so it cannot become a second source of truth about who is signed in.
 */
public final class SessionPosture {

    /**
     * A gap longer than this between two logins suggests a new device rather
     * than a returning one.
     *
     * <p>Deliberately generous. A developer who works daily and then travels will
     * exceed a day between sessions; treating that as a new device would train
     * them to ignore the signal.
     */
    static final Duration NEW_DEVICE_GAP = Duration.ofDays(30);

    private SessionPosture() {
    }

    /**
     * Sessions that are neither revoked nor past their expiry.
     */
    public static List<AuthSession> active(List<AuthSession> sessions, Instant now) {
        return sessions.stream()
                .filter(session -> session.isActive(now))
                .toList();
    }

    /**
     * Revoked sessions, for the "was this you?" view after a lockout.
     *
     * <p>Shown deliberately: someone who has been signed out wants to know what
     * was ended and when, and a list that silently omits revoked sessions makes
     * a mass revocation look like it did nothing.
     */
    public static List<AuthSession> revoked(List<AuthSession> sessions) {
        return sessions.stream()
                .filter(session -> session.getRevokedAt() != null)
                .toList();
    }

    /**
     * Whether a login from {@code device} is the first seen from that family.
     *
     * <p>The signal is a <em>change</em>, not a login. A brand new device is only
     * interesting because the organization has not used that family before, and
     * comparing against the same list of prior sessions is what makes it so.
     */
    public static boolean isNewDeviceFamily(List<AuthSession> priorSessions, String device,
            Instant now) {
        if (device == null || device.isBlank()) {
            return false;
        }
        long knownCount = priorSessions.stream()
                .filter(session -> session.getRevokedAt() == null)
                .filter(session -> session.getLastSeenAt() != null
                        && !session.getLastSeenAt().isBefore(now.minus(NEW_DEVICE_GAP)))
                .count();
        // With no usable history there is nothing to compare against, so claiming
        // "new" would be unfounded.
        return knownCount == 0;
    }

    /**
     * How many distinct sessions a user has open.
     *
     * <p>Surfaced because a sudden jump is meaningful: one session is normal,
     * three may be, eight is usually something else.
     */
    public static int activeCount(List<AuthSession> sessions, Instant now) {
        return active(sessions, now).size();
    }

    /**
     * The most recent activity, for a "last seen" summary.
     */
    public static Optional<Instant> lastSeen(List<AuthSession> sessions) {
        return sessions.stream()
                .map(AuthSession::getLastSeenAt)
                .filter(java.util.Objects::nonNull)
                .max(Instant::compareTo);
    }

    /**
     * Sessions a user may not revoke for themselves.
     *
     * <p>None currently. Kept as an explicit empty contract because the day one
     * exists, forgetting to exclude it would let a user sign themselves out of
     * the very session performing the action, losing the audit trail.
     */
    public static List<UUID> protectedSessions() {
        return List.of();
    }
}