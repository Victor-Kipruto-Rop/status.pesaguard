package com.pesaguard.backend.securitycenter.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.PageRequest;

import com.pesaguard.backend.security.principals.AuthenticatedUser;
import com.pesaguard.backend.security.sessions.AuthSession;
import com.pesaguard.backend.security.sessions.AuthSessionRepository;
import com.pesaguard.backend.securitycenter.domain.SecurityEventType;
import com.pesaguard.backend.securitycenter.domain.SessionDevice;

/**
 * Applies the sign-in device policy and records unfamiliar-device signals.
 *
 * <p>Verification is required for an unknown device or a recognized device
 * after more than one hour without session activity. The post-login signal
 * recording below remains advisory and cannot undo an authenticated sign-in:
 *
 * <ul>
 *   <li>Device matching is deliberately coarse (see
 *       {@link SessionDevice#sameDeviceFamily}).</li>
 *   <li>Signal recording runs in its own transaction after a session exists and
 *       never rolls that successful sign-in back.</li>
 * </ul>
 */
@Service
public class UnfamiliarDeviceDetector {

    private static final Duration EMAIL_VERIFICATION_IDLE_WINDOW = Duration.ofHours(1);
    private static final int DEVICE_HISTORY_LIMIT = 100;

    private final AuthSessionRepository sessionRepository;
    private final SecurityEventService securityEventService;
    private final Clock clock;

    public UnfamiliarDeviceDetector(
            AuthSessionRepository sessionRepository,
            SecurityEventService securityEventService,
            Clock clock) {
        this.sessionRepository = sessionRepository;
        this.securityEventService = securityEventService;
        this.clock = clock;
    }

    /**
     * Requires email verification for an unknown device or when the account has
     * had no session activity for more than one hour.
     */
    public boolean requiresEmailVerification(UUID userId, String deviceLabel) {
        if (deviceLabel == null || deviceLabel.isBlank()) {
            return true;
        }

        List<AuthSession> knownDevices = sessionRepository.findRecentDeviceSessionsByUserId(
                userId, PageRequest.of(0, DEVICE_HISTORY_LIMIT));
        boolean recognisedDevice = knownDevices.stream()
                .anyMatch(session -> SessionDevice.sameDeviceFamily(
                        Optional.ofNullable(session.getDeviceLabel()), deviceLabel));
        if (!recognisedDevice) {
            return true;
        }

        Instant lastActivity = sessionRepository.findLatestActivityByUserId(userId);
        return lastActivity == null
                || lastActivity.isBefore(clock.instant().minus(EMAIL_VERIFICATION_IDLE_WINDOW));
    }

    /**
     * Records a signal if this device family is new to the account.
     *
     * <p>Called after the session is issued, and compared against every <em>other</em>
     * live session so the one just created cannot vouch for itself -- otherwise the
     * first sign-in from a device would immediately match itself and never be seen.
     *
     * @param newSessionId the session created by this sign-in
     * @return true if a signal was recorded
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean evaluate(AuthenticatedUser principal, String deviceLabel, UUID newSessionId) {
        if (principal == null || deviceLabel == null || deviceLabel.isBlank()) {
            // No usable client description. Absence of evidence is not evidence.
            return false;
        }
        try {
            List<AuthSession> previous = sessionRepository
                    .findActiveByUserIdOrderByLastSeenAtDesc(principal.userId(), clock.instant())
                    .stream()
                    .filter(session -> !session.getId().equals(newSessionId))
                    .toList();
            if (previous.isEmpty()) {
                // First session this account has ever had. There is nothing to be
                // unfamiliar against, and flagging it would mark every signup.
                return false;
            }
            boolean recognised = previous.stream()
                    .anyMatch(session -> SessionDevice.sameDeviceFamily(
                            Optional.ofNullable(session.getDeviceLabel()), deviceLabel));
            if (recognised) {
                return false;
            }
            securityEventService.record(principal.organizationId(),
                    SecurityEventType.UNFAMILIAR_DEVICE_SIGNIN,
                    principal.userId(), "user",
                    "Signed in from a device not previously used for this account: "
                            + deviceLabel + ". An observation, not a confirmed compromise.");
            return true;
        } catch (RuntimeException unavailable) {
            // Deliberately swallowed. The sign-in already succeeded and must not be
            // undone because an advisory signal could not be written.
            return false;
        }
    }
}