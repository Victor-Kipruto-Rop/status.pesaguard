package com.pesaguard.backend.security.throttling;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.pesaguard.backend.common.exception.TooManyRequestsException;
import com.pesaguard.backend.config.ApplicationProperties;

/**
 * Subject-hash request throttle backed by the {@code login_throttles} table.
 *
 * <p>Subjects are HMAC hashes, never raw identifiers, so the table cannot be
 * used to enumerate accounts or addresses. This service is deliberately not
 * login-specific: the same primitive bounds unauthenticated endpoints such as
 * invitation acceptance, where every attempt is counted rather than only
 * failures.
 */
@Service
public class RequestThrottleService {

    private final JdbcTemplate jdbcTemplate;
    private final Duration failureWindow;
    private final Duration invitationAcceptWindow;
    private final Clock clock;

    public RequestThrottleService(
            JdbcTemplate jdbcTemplate,
            ApplicationProperties properties,
            Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.failureWindow = properties.security().loginFailureWindow();
        this.invitationAcceptWindow = properties.security().invitationAcceptWindow();
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void assertAllowed(String type, String subjectHash, int failureLimit) {
        Instant blockedUntil = jdbcTemplate.query(
                "select blocked_until from login_throttles where subject_type = ? and subject_hash = ?",
                resultSet -> resultSet.next()
                        ? toInstant(resultSet.getTimestamp("blocked_until"))
                        : null,
                type,
                subjectHash);
        if (blockedUntil != null && blockedUntil.isAfter(clock.instant())) {
            long retry = Math.max(1, blockedUntil.getEpochSecond() - clock.instant().getEpochSecond());
            throw new TooManyRequestsException("Too many requests. Try again later.", retry);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Instant blockedUntil(String type, String subjectHash) {
        Instant blockedUntil = jdbcTemplate.query(
                "select blocked_until from login_throttles where subject_type = ? and subject_hash = ?",
                resultSet -> resultSet.next()
                        ? toInstant(resultSet.getTimestamp("blocked_until"))
                        : null,
                type,
                subjectHash);
        return blockedUntil != null && blockedUntil.isAfter(clock.instant()) ? blockedUntil : null;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(String type, String subjectHash, int failureLimit) {
        upsert(type, subjectHash, failureLimit, failureWindow);
    }

    /**
     * Counts every attempt, not only failures. Used for unauthenticated endpoints
     * whose successful path is itself expensive, so that throughput is bounded
     * regardless of outcome.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordAttempt(String type, String subjectHash, int attemptLimit, Duration window) {
        upsert(type, subjectHash, attemptLimit, window);
    }

    private void upsert(String type, String subjectHash, int limit, Duration window) {
        long windowSeconds = window.toSeconds();
        jdbcTemplate.queryForObject("""
                insert into login_throttles (
                    subject_type, subject_hash, failure_count, window_started_at, blocked_until, created_at, updated_at
                ) values (?, ?, 1, current_timestamp, null, current_timestamp, current_timestamp)
                on conflict (subject_type, subject_hash) do update set
                    failure_count = case
                        when login_throttles.window_started_at <= current_timestamp - (? * interval '1 second')
                        then 1 else login_throttles.failure_count + 1 end,
                    window_started_at = case
                        when login_throttles.window_started_at <= current_timestamp - (? * interval '1 second')
                        then current_timestamp else login_throttles.window_started_at end,
                    blocked_until = case
                        when (case
                            when login_throttles.window_started_at <= current_timestamp - (? * interval '1 second')
                            then 1 else login_throttles.failure_count + 1 end) >= ?
                        then current_timestamp + (? * interval '1 second')
                        else null end,
                    updated_at = current_timestamp
                returning blocked_until
                """,
                (resultSet, rowNumber) -> toInstant(resultSet.getTimestamp("blocked_until")),
                type,
                subjectHash,
                windowSeconds,
                windowSeconds,
                windowSeconds,
                limit,
                windowSeconds);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void clear(String type, String subjectHash) {
        jdbcTemplate.update(
                "delete from login_throttles where subject_type = ? and subject_hash = ?",
                type,
                subjectHash);
    }

    private Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
