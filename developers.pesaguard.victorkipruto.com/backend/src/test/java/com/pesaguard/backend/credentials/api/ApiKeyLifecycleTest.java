package com.pesaguard.backend.credentials.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class ApiKeyLifecycleTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private final UUID organizationId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID environmentId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    private ApiKey createdKey() {
        return ApiKey.create(organizationId, projectId, environmentId, "worker",
                "pgk_abcdefghijklmnop", "hash-value", "projects:read", NOW.plusSeconds(3600), userId);
    }

    private ApiKey activeKey() {
        ApiKey key = createdKey();
        key.activate();
        return key;
    }

    @Test
    void issuanceStartsCreatedAndIsActivatedExplicitly() {
        ApiKey key = createdKey();

        assertThat(key.getStatus()).isEqualTo(ApiKeyStatus.CREATED);
        assertThat(key.isUsable(NOW)).isFalse();

        key.activate();

        assertThat(key.getStatus()).isEqualTo(ApiKeyStatus.ACTIVE);
        assertThat(key.isUsable(NOW)).isTrue();
    }

    @Test
    void onlyACreatedKeyCanBeActivated() {
        ApiKey key = activeKey();

        assertThatThrownBy(key::activate).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void suspendAndResumeFollowTheLifecycle() {
        ApiKey key = activeKey();

        key.suspend(NOW.plusSeconds(60));
        assertThat(key.getStatus()).isEqualTo(ApiKeyStatus.SUSPENDED);
        assertThat(key.isUsable(NOW.plusSeconds(60))).isFalse();
        assertThat(key.getSuspendedAt()).isEqualTo(NOW.plusSeconds(60));

        key.resume();
        assertThat(key.getStatus()).isEqualTo(ApiKeyStatus.ACTIVE);
        assertThat(key.isUsable(NOW.plusSeconds(60))).isTrue();
        assertThat(key.getSuspendedAt()).isNull();
    }

    @Test
    void onlyASuspendedKeyCanBeResumed() {
        ApiKey key = activeKey();

        assertThatThrownBy(key::resume).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void revokedKeysAreTerminalAndIdempotent() {
        ApiKey key = activeKey();

        key.revoke(NOW.plusSeconds(60));
        assertThat(key.getStatus()).isEqualTo(ApiKeyStatus.REVOKED);
        assertThat(key.isUsable(NOW.plusSeconds(60))).isFalse();

        key.revoke(NOW.plusSeconds(120));
        assertThat(key.getRevokedAt()).isEqualTo(NOW.plusSeconds(60));

        assertThatThrownBy(() -> key.suspend(NOW.plusSeconds(180)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(key::resume).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aSuspendedKeyCanBeRevokedButNotTheReverse() {
        ApiKey key = activeKey();
        key.suspend(NOW.plusSeconds(30));
        key.revoke(NOW.plusSeconds(60));
        assertThat(key.getStatus()).isEqualTo(ApiKeyStatus.REVOKED);

        assertThatThrownBy(() -> key.resume()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void expiredKeysStopAuthenticating() {
        ApiKey key = activeKey();

        assertThat(key.isUsable(NOW)).isTrue();
        assertThat(key.isUsable(NOW.plusSeconds(3601))).isFalse();
        assertThat(key.effectiveStatus(NOW.plusSeconds(3601))).isEqualTo(ApiKeyStatus.EXPIRED);

        key.markExpired(NOW.plusSeconds(3601));
        assertThat(key.getStatus()).isEqualTo(ApiKeyStatus.EXPIRED);
        assertThatThrownBy(() -> key.revoke(NOW.plusSeconds(3700)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void revokingWinsOverExpiryForStatusReporting() {
        ApiKey key = activeKey();
        key.revoke(NOW.plusSeconds(60));

        assertThat(key.effectiveStatus(NOW.plusSeconds(99999))).isEqualTo(ApiKeyStatus.REVOKED);
    }

    @Test
    void usageMetadataIsRecordedWithoutTheSecret() {
        ApiKey key = activeKey();

        key.recordUsage(NOW.plusSeconds(10), "203.0.113.5");
        key.recordUsage(NOW.plusSeconds(20), "203.0.113.6");

        assertThat(key.getRequestCount()).isEqualTo(2);
        assertThat(key.getLastUsedAt()).isEqualTo(NOW.plusSeconds(20));
        assertThat(key.getLastUsedIp()).isEqualTo("203.0.113.6");
        assertThat(key.getSecretHash()).isEqualTo("hash-value");
    }

    @Test
    void overlongAddressesAreTruncatedToTheColumnWidth() {
        ApiKey key = activeKey();

        key.recordUsage(NOW, "x".repeat(120));

        assertThat(key.getLastUsedIp()).hasSize(45);
    }

    @Test
    void ipRestrictionsRoundTrip() {
        ApiKey key = activeKey();

        key.restrictToIps(Set.of("203.0.113.0/24", "198.51.100.7"));

        assertThat(key.getIpAllowlist()).isEqualTo("198.51.100.7,203.0.113.0/24");
        key.restrictToIps(Set.of());
        assertThat(key.getIpAllowlist()).isNull();
    }

    @Test
    void rotationLineageIsRecordedOnTheNewKey() {
        ApiKey previous = activeKey();
        ApiKey rotated = activeKey();

        rotated.markRotatedFrom(previous.getId());

        assertThat(rotated.getRotatedFromId()).isEqualTo(previous.getId());
        assertThat(previous.getRotatedFromId()).isNull();
    }

    @Test
    void scopesSurviveEncoding() {
        ApiKey key = activeKey();

        assertThat(key.scopeSet()).containsExactly("projects:read");
    }

    @Test
    void statusHelpersDescribeTheLifecycle() {
        assertThat(ApiKeyStatus.ACTIVE.canAuthenticate()).isTrue();
        assertThat(ApiKeyStatus.CREATED.canAuthenticate()).isFalse();
        assertThat(ApiKeyStatus.SUSPENDED.canAuthenticate()).isFalse();
        assertThat(ApiKeyStatus.REVOKED.isTerminal()).isTrue();
        assertThat(ApiKeyStatus.EXPIRED.isTerminal()).isTrue();
        assertThat(ApiKeyStatus.SUSPENDED.isTerminal()).isFalse();
    }
}