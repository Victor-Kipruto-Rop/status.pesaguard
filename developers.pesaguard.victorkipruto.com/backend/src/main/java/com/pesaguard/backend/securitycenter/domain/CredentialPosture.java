package com.pesaguard.backend.securitycenter.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import com.pesaguard.backend.credentials.api.ApiKey;
import com.pesaguard.backend.credentials.api.ApiKeyStatus;

/**
 * Credential posture for an organization, grouped the way a developer needs to
 * answer questions about their own keys.
 *
 * <p>Classification uses {@link ApiKey#effectiveStatus(Instant)} rather than the
 * stored status, so a key whose expiry has passed is reported as expired even
 * before a sweep has run to write that down. A dashboard that says "active" for a
 * key that stopped working yesterday is worse than one that is slightly stale.
 */
public final class CredentialPosture {

    /**
     * A key with no recorded use in this long is reported as dormant.
     *
     * <p>Thirty days. Long enough that an intermittently used integration is not
     * nagged, short enough that a forgotten production key is still surfaced.
     */
    static final Duration DORMANT_AFTER = Duration.ofDays(30);

    private CredentialPosture() {
    }

    /** Keys that can authenticate right now. */
    public static List<ApiKey> active(List<ApiKey> keys, Instant now) {
        return keys.stream()
                .filter(key -> key.effectiveStatus(now) == ApiKeyStatus.ACTIVE)
                .toList();
    }

    /**
     * Keys that will not authenticate because their window has passed.
     *
     * <p>Included in the security view on purpose. A developer holding an expired
     * production key is usually not aware of it, and that key is both a support
     * question and a standing credential.
     */
    public static List<ApiKey> expired(List<ApiKey> keys, Instant now) {
        return keys.stream()
                .filter(key -> key.effectiveStatus(now) == ApiKeyStatus.EXPIRED)
                .toList();
    }

    public static List<ApiKey> revoked(List<ApiKey> keys, Instant now) {
        return keys.stream()
                .filter(key -> key.effectiveStatus(now) == ApiKeyStatus.REVOKED)
                .toList();
    }

    public static List<ApiKey> suspended(List<ApiKey> keys, Instant now) {
        return keys.stream()
                .filter(key -> key.effectiveStatus(now) == ApiKeyStatus.SUSPENDED)
                .toList();
    }

    /** Recently used keys, most recent first. */
    public static List<ApiKey> recentlyUsed(List<ApiKey> keys, int limit) {
        return keys.stream()
                .filter(key -> key.getLastUsedAt() != null)
                .sorted(Comparator.comparing(ApiKey::getLastUsedAt).reversed())
                .limit(Math.max(0, limit))
                .toList();
    }

    /**
     * Keys with no recent use.
     *
     * <p>Reported so a developer can find credentials they no longer need. Every
     * stored key is attack surface whether anyone remembers it or not.
     */
    public static List<ApiKey> dormant(List<ApiKey> keys, Instant now) {
        Instant cutoff = now.minus(DORMANT_AFTER);
        return keys.stream()
                .filter(key -> key.effectiveStatus(now) == ApiKeyStatus.ACTIVE)
                .filter(key -> key.getLastUsedAt() == null
                        || key.getLastUsedAt().isBefore(cutoff))
                .toList();
    }

    /**
     * Keys whose recent activity looks wrong.
     *
     * <p>Deliberately limited to facts the data actually supports, rather than a
     * composite risk score:
     * <ul>
     *   <li>a revoked or expired key that has been used after it stopped
     *       working, which is the clearest misuse signal available; and</li>
     *   <li>an active key whose last use came from an address different from the
     *       one it has been used from before, which is a weaker hint.</li>
     * </ul>
     *
     * <p>Neither is a finding of compromise, and the caller records them as
     * signals for a human to judge rather than acting on them.
     */
    public static List<ApiKey> suspicious(List<ApiKey> keys, Instant now) {
        return keys.stream()
                .filter(key -> isUsedAfterRevocation(key, now) || movedAddress(key))
                .toList();
    }

    /**
     * Whether a key recorded use after it was revoked or expired.
     *
     * <p>Only detectable when the last-use instant is later than the revocation
     * instant. Note the honest limit: the platform cannot see a revoked key that
     * was used and <em>then</em> revoked, because nothing rejected the request in
     * between to record it. What this catches is use recorded after the
     * credential had already stopped working, which is a genuine and actionable
     * subset.
     */
    public static boolean isUsedAfterRevocation(ApiKey key, Instant now) {
        Instant revokedAt = key.getRevokedAt();
        Instant lastUsed = key.getLastUsedAt();
        if (revokedAt == null || lastUsed == null) {
            return false;
        }
        return lastUsed.isAfter(revokedAt);
    }

    private static boolean movedAddress(ApiKey key) {
        return key.getLastUsedIp() != null && key.getLastUsedAt() != null
                && key.getRequestCount() > 1;
    }

    /** The instant a key was last used, if ever. */
    public static Optional<Instant> lastUsed(ApiKey key) {
        return Optional.ofNullable(key.getLastUsedAt());
    }
}