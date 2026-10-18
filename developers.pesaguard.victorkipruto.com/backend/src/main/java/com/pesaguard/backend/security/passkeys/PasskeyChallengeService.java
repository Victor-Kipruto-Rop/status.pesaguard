package com.pesaguard.backend.security.passkeys;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.pesaguard.backend.common.exception.BusinessException;

@Service
public class PasskeyChallengeService {

    private final PasskeyChallengeRepository repository;
    private final PasskeyProperties properties;
    private final Clock clock;

    public PasskeyChallengeService(
            PasskeyChallengeRepository repository, PasskeyProperties properties, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public UUID issue(String purpose, UUID userId, UUID organizationId, String requestJson) {
        Instant now = clock.instant();
        repository.deleteExpired(now);
        UUID id = UUID.randomUUID();
        repository.save(PasskeyChallenge.create(
                id, purpose, userId, organizationId, requestJson, now, now.plus(properties.challengeTtl())));
        return id;
    }

    /**
     * Marks a challenge spent in its own transaction before the response is
     * parsed or verified. Even a malformed or cryptographically invalid response
     * therefore cannot roll the challenge back into a reusable state.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ConsumedChallenge consume(UUID id) {
        PasskeyChallenge challenge = repository.findByIdForUpdate(id)
                .orElseThrow(this::invalidChallenge);
        Instant now = clock.instant();
        if (challenge.getConsumedAt() != null) throw invalidChallenge();
        challenge.consume(now);
        repository.saveAndFlush(challenge);
        return new ConsumedChallenge(challenge.getPurpose(), challenge.getUserId(),
                challenge.getOrganizationId(), challenge.getRequestJson(), challenge.getExpiresAt(), now);
    }

    public void requireValid(ConsumedChallenge challenge, String purpose, UUID userId, UUID organizationId) {
        if (!challenge.expiresAt().isAfter(challenge.consumedAt())
                || !purpose.equals(challenge.purpose())
                || !java.util.Objects.equals(userId, challenge.userId())
                || !java.util.Objects.equals(organizationId, challenge.organizationId())) {
            throw invalidChallenge();
        }
    }

    private BusinessException invalidChallenge() {
        return new BusinessException(org.springframework.http.HttpStatus.UNAUTHORIZED,
                "PASSKEY_CHALLENGE_INVALID", "This passkey challenge is invalid, expired, or already used.");
    }

    public record ConsumedChallenge(
            String purpose, UUID userId, UUID organizationId, String requestJson,
            Instant expiresAt, Instant consumedAt) {
    }
}
