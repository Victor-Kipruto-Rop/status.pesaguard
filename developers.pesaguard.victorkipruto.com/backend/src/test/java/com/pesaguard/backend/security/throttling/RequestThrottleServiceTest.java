package com.pesaguard.backend.security.throttling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;

import com.pesaguard.backend.common.exception.TooManyRequestsException;
import com.pesaguard.backend.config.ApplicationProperties;
import com.pesaguard.backend.security.TestProperties;

class RequestThrottleServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final RequestThrottleService service = new RequestThrottleService(
            jdbcTemplate, properties(), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void anActiveBlockIsRejectedWithARetryAfter() {
        stubStoredBlock(NOW.plusSeconds(120));

        assertThatThrownBy(() -> service.assertAllowed("invite_ip", "hash", 30))
                .isInstanceOf(TooManyRequestsException.class)
                .hasMessageContaining("Too many requests")
                .satisfies(exception -> assertThat(((TooManyRequestsException) exception).retryAfterSeconds())
                        .isPositive());
    }

    @Test
    void anExpiredBlockNoLongerRejects() {
        stubStoredBlock(NOW.minusSeconds(5));

        service.assertAllowed("invite_ip", "hash", 30);
    }

    @Test
    void anUnknownSubjectIsAllowed() {
        stubStoredBlock(null);

        service.assertAllowed("invite_token", "hash", 30);
    }

    @Test
    void everyAttemptIsCountedNotOnlyFailures() {
        stubUpsertResult(null);

        service.recordAttempt("invite_ip", "hash", 30, Duration.ofMinutes(15));
        service.recordAttempt("invite_ip", "hash", 30, Duration.ofMinutes(15));

        verify(jdbcTemplate, times(2)).queryForObject(anyString(), any(RowMapper.class), any(Object[].class));
    }

    @Test
    void successfulLoginClearsOnlyTheAccountSubject() {
        service.clear("account", "hash");

        verify(jdbcTemplate).update(anyString(), any(Object[].class));
        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class), any(Object[].class));
    }

    private void stubStoredBlock(Instant blockedUntil) {
        doReturn(blockedUntil).when(jdbcTemplate)
                .query(anyString(), any(ResultSetExtractor.class), anyString(), anyString());
    }

    private void stubUpsertResult(Instant blockedUntil) {
        doReturn(blockedUntil).when(jdbcTemplate)
                .queryForObject(anyString(), any(RowMapper.class), any(Object[].class));
    }

    private ApplicationProperties properties() {
        return TestProperties.platform();
    }
}