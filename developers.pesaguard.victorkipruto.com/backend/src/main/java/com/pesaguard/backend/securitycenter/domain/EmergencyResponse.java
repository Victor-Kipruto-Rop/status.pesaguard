package com.pesaguard.backend.securitycenter.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.pesaguard.backend.credentials.api.ApiKey;
import com.pesaguard.backend.security.sessions.AuthSession;

/**
 * The rules for emergency response actions.
 *
 * <p>Emergency actions are deliberately awkward. A developer panicking because a
 * key leaked should not be asked to select a checkbox, but every action that
 * affects more than one credential requires a stated reason, because "revoke
 * everything" is reversible only by reissuing and the reason is what tells the
 * next person whether it was a real compromise or a false alarm.
 *
 * <p>Ordering matters here. Credentials are terminated before sessions, because
 * an API key is what unattended software uses; a session is a human. If the
 * process is interrupted, the automation is already stopped.
 */
public final class EmergencyResponse {

    /**
     * Keys beyond this count are refused without an explicit override.
     *
     * <p>A guard against a misclick that revokes an organization's entire
     * production estate. The bound is high enough to be a real incident response
     * and low enough to catch a mistake.
     */
    public static final int MAX_BULK_KEYS = 100;

    /**
     * Sessions beyond this count are refused without an explicit override.
     */
    public static final int MAX_BULK_SESSIONS = 500;

    private EmergencyResponse() {
    }

    /**
     * Whether a bulk credential termination is allowed.
     *
     * <p>An empty selection is refused rather than treated as a no-op: a caller
     * that computed nothing and reports success has given false assurance during
     * exactly the moment false assurance is most damaging.
     */
    public static boolean canTerminateKeys(List<ApiKey> keys, boolean force) {
        if (keys == null || keys.isEmpty()) {
            return false;
        }
        return force || keys.size() <= MAX_BULK_KEYS;
    }

    public static boolean canTerminateSessions(List<AuthSession> sessions, boolean force) {
        if (sessions == null || sessions.isEmpty()) {
            return false;
        }
        return force || sessions.size() <= MAX_BULK_SESSIONS;
    }

    /**
     * Keys that can no longer be used once the action is applied.
     *
     * <p>Already-terminal keys are excluded rather than reported as affected. A
     * count that includes them overstates the blast radius of an action and makes
     * the response log useless for working out what a developer must now replace.
     */
    public static List<ApiKey> terminableKeys(List<ApiKey> keys, Instant now) {
        return keys.stream()
                .filter(key -> !key.effectiveStatus(now).isTerminal())
                .toList();
    }

    /**
     * Sessions still open and therefore revokable.
     */
    public static List<AuthSession> revocableSessions(List<AuthSession> sessions, Instant now) {
        return sessions.stream()
                .filter(session -> session.isActive(now))
                .toList();
    }

    /**
     * Validates a stated reason.
     *
     * <p>Trimmed and length-bounded, and never returned raw from a caller, so a
     * control character in a reason cannot end up in an audit record.
     */
    public static String normaliseReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A reason is required for an emergency action");
        }
        String trimmed = reason.trim();
        if (trimmed.length() > 500) {
            throw new IllegalArgumentException("A reason must be at most 500 characters");
        }
        StringBuilder cleaned = new StringBuilder(trimmed.length());
        for (int index = 0; index < trimmed.length(); index++) {
            char character = trimmed.charAt(index);
            // Drops control characters, including newlines, which would otherwise
            // corrupt a single-line audit log entry.
            if (character >= 0x20 && character != 0x7F) {
                cleaned.append(character);
            }
        }
        String result = cleaned.toString();
        if (result.isBlank()) {
            throw new IllegalArgumentException("A reason is required for an emergency action");
        }
        return result;
    }

    /** The distinct credentials a bulk action would affect, for confirmation. */
    public static java.util.Set<UUID> affectedKeyIds(List<ApiKey> keys) {
        return keys.stream().map(ApiKey::getId).collect(java.util.stream.Collectors.toSet());
    }
}