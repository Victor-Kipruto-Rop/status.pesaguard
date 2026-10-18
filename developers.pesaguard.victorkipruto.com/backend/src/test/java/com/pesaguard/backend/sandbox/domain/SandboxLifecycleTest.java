package com.pesaguard.backend.sandbox.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Sandbox lifecycle: creation, activation, expiration, reset, suspension,
 * deletion.
 *
 * <p>The behaviour worth protecting is expiry: a sandbox past its deadline must
 * be inert even before the expiry sweep has run, because a sweep that is late,
 * failing or absent must not leave a sandbox executing indefinitely.
 */
class SandboxLifecycleTest {

    private static final Instant NOW = Instant.parse("2026-04-01T00:00:00Z");

    private final UUID organizationId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID environmentId = UUID.randomUUID();
    private final UUID actor = UUID.randomUUID();

    private Sandbox sandbox(Duration ttl) {
        return Sandbox.create(organizationId, projectId, environmentId, "test sandbox",
                "for integration tests", ttl, actor, NOW);
    }

    @Test
    void aNewSandboxIsProvisioningAndNotExecutable() {
        Sandbox sandbox = sandbox(null);

        assertThat(sandbox.getStatus()).isEqualTo(SandboxStatus.PROVISIONING);
        assertThat(sandbox.canExecute(NOW)).isFalse();
        assertThat(sandbox.getActivatedAt()).isNull();
    }

    @Test
    void activationEnablesExecution() {
        Sandbox sandbox = sandbox(null);
        sandbox.activate(NOW);

        assertThat(sandbox.getStatus()).isEqualTo(SandboxStatus.ACTIVE);
        assertThat(sandbox.canExecute(NOW)).isTrue();
        assertThat(sandbox.getActivatedAt()).isEqualTo(NOW);
    }

    @Test
    void onlyAProvisioningSandboxCanBeActivated() {
        Sandbox sandbox = sandbox(null);
        sandbox.activate(NOW);

        assertThatThrownBy(() -> sandbox.activate(NOW.plusSeconds(1)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anExpiredSandboxCannotBeActivated() {
        Sandbox sandbox = sandbox(Duration.ofHours(1));

        assertThatThrownBy(() -> sandbox.activate(NOW.plusSeconds(3601)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void suspensionStopsExecutionButRetainsData() {
        Sandbox sandbox = sandbox(null);
        sandbox.activate(NOW);
        sandbox.suspend(NOW.plusSeconds(60));

        assertThat(sandbox.getStatus()).isEqualTo(SandboxStatus.SUSPENDED);
        assertThat(sandbox.canExecute(NOW.plusSeconds(60))).isFalse();
        assertThat(sandbox.getSuspendedAt()).isEqualTo(NOW.plusSeconds(60));
    }

    @Test
    void suspendingIsIdempotent() {
        Sandbox sandbox = sandbox(null);
        sandbox.activate(NOW);
        sandbox.suspend(NOW.plusSeconds(60));
        sandbox.suspend(NOW.plusSeconds(120));

        // A sweep or a retried request may repeat the call; the second run must not
        // throw or move the timestamp.
        assertThat(sandbox.getSuspendedAt()).isEqualTo(NOW.plusSeconds(60));
    }

    @Test
    void resumeReEnablesExecution() {
        Sandbox sandbox = sandbox(null);
        sandbox.activate(NOW);
        sandbox.suspend(NOW.plusSeconds(60));
        sandbox.resume(NOW.plusSeconds(120));

        assertThat(sandbox.getStatus()).isEqualTo(SandboxStatus.ACTIVE);
        assertThat(sandbox.canExecute(NOW.plusSeconds(120))).isTrue();
        assertThat(sandbox.getSuspendedAt()).isNull();
    }

    @Test
    void anExpiredSandboxCannotBeResumed() {
        Sandbox sandbox = sandbox(Duration.ofHours(1));
        sandbox.activate(NOW);
        sandbox.suspend(NOW.plusSeconds(60));

        assertThatThrownBy(() -> sandbox.resume(NOW.plusSeconds(7200)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expired");
        assertThat(sandbox.getStatus()).isEqualTo(SandboxStatus.EXPIRED);
    }

    @Test
    void expiryTakesEffectWithoutTheSweepHavingRun() {
        Sandbox sandbox = sandbox(Duration.ofHours(1));
        sandbox.activate(NOW);

        // The stored status is still ACTIVE; only the clock says otherwise. Reading
        // the raw status would keep a lapsed sandbox executing.
        assertThat(sandbox.getStatus()).isEqualTo(SandboxStatus.ACTIVE);
        assertThat(sandbox.effectiveStatus(NOW.plusSeconds(3601)))
                .isEqualTo(SandboxStatus.EXPIRED);
        assertThat(sandbox.canExecute(NOW.plusSeconds(3601))).isFalse();
        assertThat(sandbox.isExpired(NOW.plusSeconds(3601))).isTrue();
    }

    @Test
    void expiryIsIdempotent() {
        Sandbox sandbox = sandbox(null);
        sandbox.activate(NOW);
        sandbox.expire(NOW.plusSeconds(10));
        sandbox.expire(NOW.plusSeconds(20));

        assertThat(sandbox.getStatus()).isEqualTo(SandboxStatus.EXPIRED);
    }

@Test
    void deletionIsTerminal() {
        Sandbox sandbox = sandbox(null);
        sandbox.activate(NOW);
        sandbox.delete(NOW.plusSeconds(60));

        assertThat(sandbox.getStatus()).isEqualTo(SandboxStatus.DELETED);
        assertThat(sandbox.canExecute(NOW.plusSeconds(60))).isFalse();
        assertThat(sandbox.getDeletedAt()).isEqualTo(NOW.plusSeconds(60));

        assertThatThrownBy(() -> sandbox.activate(NOW.plusSeconds(70)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> sandbox.suspend(NOW.plusSeconds(70)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> sandbox.recordReset(NOW.plusSeconds(70)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void deletionIsIdempotent() {
        Sandbox sandbox = sandbox(null);
        sandbox.activate(NOW);
        sandbox.delete(NOW.plusSeconds(60));
        sandbox.delete(NOW.plusSeconds(120));

        assertThat(sandbox.getDeletedAt()).isEqualTo(NOW.plusSeconds(60));
    }

    @Test
    void ttlOutsideTheAllowedRangeIsRejected() {
        assertThatThrownBy(() -> sandbox(Duration.ofMinutes(5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sandbox(Duration.ofDays(365)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(sandbox(Duration.ofDays(7))).isNotNull();
    }

    @Test
    void aDefaultNameIsUsedWhenNoneIsSupplied() {
        Sandbox sandbox = Sandbox.create(organizationId, projectId, environmentId,
                "  ", null, null, actor, NOW);

        assertThat(sandbox.getName()).isEqualTo("sandbox");
        assertThat(sandbox.getDescription()).isNull();
    }


    @Test
    void resetDoesNotChangeLifecycleStatus() {
        Sandbox sandbox = sandbox(null);
        sandbox.activate(NOW);
        sandbox.suspend(NOW.plusSeconds(60));

        sandbox.recordReset(NOW.plusSeconds(90));

        // Resetting data must not quietly reactivate a suspended sandbox.
        assertThat(sandbox.getStatus()).isEqualTo(SandboxStatus.SUSPENDED);
        assertThat(sandbox.canExecute(NOW.plusSeconds(90))).isFalse();
    }

    @Test
    void resetIsCounted() {
        Sandbox sandbox = sandbox(null);
        sandbox.activate(NOW);
        sandbox.recordReset(NOW);
        sandbox.recordReset(NOW.plusSeconds(10));

        assertThat(sandbox.getResetCount()).isEqualTo(2);
        assertThat(sandbox.getLastResetAt()).isEqualTo(NOW.plusSeconds(10));
    }
}
