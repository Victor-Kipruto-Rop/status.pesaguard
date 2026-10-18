package com.pesaguard.backend.security.tokens;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Emergency revocation for access tokens that cannot otherwise be withdrawn.
 *
 * <p>Stateless JWTs buy scale by giving up one property: a token stays valid until
 * it expires, whatever happens to the session behind it. That is acceptable for
 * an ordinary logout. It is not acceptable for the case the access token exists
 * to protect against — discovering a stolen token and needing the theft to stop
 * <em>now</em> rather than in four minutes.
 *
 * <p>This is that backstop, and it is deliberately not the normal path. Every
 * call here writes to Redis, which is a network round trip on the authentication
 * hot path. It is consulted only when an operator or an incident revokes a session
 * out of band; the routine path (logout, password change, device removal) revokes
 * the refresh-token family, which ends the session for every subsequent refresh
 * without touching this.
 *
 * <p>Entries expire with the token they refer to rather than being swept
 * separately. A revocation for a token that has already expired is a no-op, so
 * there is nothing to reconcile, and no background job that can fall behind and
 * silently leave a revoked token usable.
 *
 * <p>Fails closed. If Redis is unreachable the token is refused: an outage of the
 * revocation store must not become an outage of revocation itself, which would
 * mean the attacker wins precisely when the defenders are distracted.
 */
@Component
public class RevokedTokenRegistry {

    private static final String KEY_PREFIX = "auth:revoked-jti:";

    /**
     * How long a revocation outlives the token it names.
     *
     * <p>One token lifetime of slack past the longest token life, so a revocation
     * written just before a token expires still outlives it.
     */
    private static final Duration RETENTION_SLACK = Duration.ofMinutes(10);

    private final StringRedisTemplate redis;
    private final Clock clock;
    private final Duration accessTokenTtl;

    public RevokedTokenRegistry(
            StringRedisTemplate redis,
            Clock clock,
            AccessTokenService accessTokenService) {
        this.redis = redis;
        this.clock = clock;
        this.accessTokenTtl = accessTokenService.accessTokenTtl();
    }

    /**
     * Marks a session's token id as revoked until it would have expired anyway.
     *
     * @param sessionId the {@code jti} carried by the token
     */
    public void revoke(UUID sessionId, Instant tokenExpiresAt) {
        Instant now = clock.instant();
        Duration remaining = Duration.between(now, tokenExpiresAt);
        if (remaining.isNegative() || remaining.isZero()) {
            // Already expired; nothing to revoke and nothing to store.
            return;
        }
        redis.opsForValue().set(
                KEY_PREFIX + sessionId, "1", remaining.plus(RETENTION_SLACK));
    }

    /**
     * Whether this token id has been revoked out of band.
     *
     * @return true when the token must be refused, including when the check itself
     *         could not be completed
     */
    public boolean isRevoked(UUID sessionId) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(KEY_PREFIX + sessionId));
        } catch (RuntimeException unavailable) {
            // Fail closed: an unreachable revocation store must not silently read as
            // "not revoked", or an outage becomes a revocation bypass.
            return true;
        }
    }
}