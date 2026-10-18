package com.pesaguard.backend.security.sessions;

import java.time.Clock;
import java.time.Duration;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.pesaguard.backend.security.principals.AuthenticatedUser;

/**
 * Records session activity at most once per minute, keeping idle-login decisions
 * accurate without adding a database write to every authenticated request.
 */
@Component
public class SessionActivityRecorder {

    private static final String ACTIVITY_WRITE_KEY_PREFIX = "auth:activity-write:";
    private static final Duration WRITE_INTERVAL = Duration.ofMinutes(1);

    private final AuthSessionRepository sessionRepository;
    private final StringRedisTemplate redis;
    private final Clock clock;

    public SessionActivityRecorder(
            AuthSessionRepository sessionRepository,
            StringRedisTemplate redis,
            Clock clock) {
        this.sessionRepository = sessionRepository;
        this.redis = redis;
        this.clock = clock;
    }

    @Transactional
    public void record(AuthenticatedUser user) {
        if (user.serviceAccount() || user.mfaEnrollmentOnly()) {
            return;
        }

        Boolean acquired = redis.opsForValue().setIfAbsent(
                ACTIVITY_WRITE_KEY_PREFIX + user.sessionId(), "1", WRITE_INTERVAL);
        if (Boolean.TRUE.equals(acquired)) {
            sessionRepository.recordActivity(user.sessionId(), clock.instant());
        }
    }
}
