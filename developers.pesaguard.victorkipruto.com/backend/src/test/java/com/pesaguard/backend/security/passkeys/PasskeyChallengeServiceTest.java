package com.pesaguard.backend.security.passkeys;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.pesaguard.backend.common.exception.BusinessException;

class PasskeyChallengeServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");
    private final PasskeyChallengeRepository repository = mock(PasskeyChallengeRepository.class);
    private final MutableClock clock = new MutableClock(NOW);
    private final PasskeyChallengeService service = new PasskeyChallengeService(
            repository,
            new PasskeyProperties("localhost", "PesaGuard", Set.of("http://localhost:5173"),
                    Duration.ofMinutes(3)),
            clock);

    @Test
    void challengeExpiresAtConfiguredShortLifetime() {
        UUID userId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        UUID id = service.issue("REGISTRATION", userId, organizationId, "{\"challenge\":\"server\"}");
        PasskeyChallenge challenge = savedChallenge();
        when(repository.findByIdForUpdate(id)).thenReturn(Optional.of(challenge));

        clock.set(challenge.getExpiresAt());
        PasskeyChallengeService.ConsumedChallenge consumed = service.consume(id);

        assertThatThrownBy(() -> service.requireValid(consumed, "REGISTRATION", userId, organizationId))
                .isInstanceOf(BusinessException.class);
        assertThat(challenge.getConsumedAt()).isEqualTo(challenge.getExpiresAt());
    }

    @Test
    void challengeIsConsumedBeforeTheResponseCanBeReplayed() {
        UUID id = service.issue("PASSWORD_MFA", UUID.randomUUID(), UUID.randomUUID(), "{\"request\":true}");
        PasskeyChallenge challenge = savedChallenge();
        when(repository.findByIdForUpdate(id)).thenReturn(Optional.of(challenge));

        PasskeyChallengeService.ConsumedChallenge consumed = service.consume(id);
        assertThat(consumed.requestJson()).isEqualTo("{\"request\":true}");
        assertThatThrownBy(() -> service.consume(id)).isInstanceOf(BusinessException.class);
        verify(repository).saveAndFlush(challenge);
    }

    @Test
    void challengeCannotBeMovedToAnotherUserOrOrganization() {
        UUID userId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        UUID id = service.issue("PASSWORD_MFA", userId, organizationId, "{\"request\":true}");
        PasskeyChallenge challenge = savedChallenge();
        when(repository.findByIdForUpdate(id)).thenReturn(Optional.of(challenge));

        PasskeyChallengeService.ConsumedChallenge consumed = service.consume(id);
        assertThatThrownBy(() -> service.requireValid(
                consumed, "PASSWORD_MFA", UUID.randomUUID(), organizationId))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.requireValid(
                consumed, "PASSWORD_MFA", userId, UUID.randomUUID()))
                .isInstanceOf(BusinessException.class);
        assertThat(challenge.getConsumedAt()).isNotNull();
    }

    private PasskeyChallenge savedChallenge() {
        ArgumentCaptor<PasskeyChallenge> captor = ArgumentCaptor.forClass(PasskeyChallenge.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;

        private MutableClock(Instant now) {
            this.now = new AtomicReference<>(now);
        }

        void set(Instant instant) {
            now.set(instant);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}
