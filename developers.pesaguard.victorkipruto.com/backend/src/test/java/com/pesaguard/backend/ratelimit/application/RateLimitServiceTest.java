package com.pesaguard.backend.ratelimit.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.pesaguard.backend.ratelimit.application.RateLimitService.RateLimitRequest;
import com.pesaguard.backend.ratelimit.domain.RateLimitPolicy;
import com.pesaguard.backend.ratelimit.domain.RateLimitScope;

/**
 * Scope composition, tenant isolation, and concurrency.
 *
 * <p>Concurrency is asserted rather than assumed: a read-then-write limiter
 * silently admits double the limit under load, which no sequential test would
 * ever reveal.
 */
class RateLimitServiceTest {

    private static final Instant T0 = Instant.parse("2026-03-15T14:37:52Z");
    private final UUID org = UUID.randomUUID();
    private final UUID key = UUID.randomUUID();
    private final UUID project = UUID.randomUUID();
    private final UUID environment = UUID.randomUUID();

    private RateLimitService service() {
        return new RateLimitService(new InMemoryRateLimitCounterStore());
    }

    private RateLimitRequest request() {
        return new RateLimitRequest(org, key, project, environment, UUID.randomUUID(),
                "/api/v1/payments", "203.0.113.10", null, T0);
    }

    private RateLimitPolicy policy(RateLimitScope scope, int limit, Duration window,
            Integer burst) {
        return RateLimitPolicy.tokenBucket(UUID.randomUUID(), org, scope, null, null,
                limit, window, burst);
    }

    @Test
    void noPoliciesMeansUnlimited() {
        var decision = service().check(request(), List.of());

        assertThat(decision.allowed()).isTrue();
        // -1 so a client can tell unlimited from "0 remaining".
        assertThat(decision.isUnlimited()).isTrue();
        assertThat(decision.limit()).isEqualTo(-1);
    }

    @Test
    void requestsAreAllowedUpToTheLimitThenRefused() {
        RateLimitPolicy policy = policy(RateLimitScope.API_KEY, 3, Duration.ofMinutes(1), null);
        RateLimitService service = service();

        for (int i = 0; i < 3; i++) {
            assertThat(service.check(request(), List.of(policy)).allowed()).isTrue();
        }
        var refused = service.check(request(), List.of(policy));
        assertThat(refused.allowed()).isFalse();
        assertThat(refused.remaining()).isZero();
        assertThat(refused.retryAfterSeconds()).isGreaterThanOrEqualTo(1L);
    }

    @Test
    void theStrictestLimitIsTheOneReported() {
        // An endpoint limit of 2 is more actionable than an org limit of 1000, and
        // it is the one the caller is told about.
        RateLimitPolicy keyLimit = policy(RateLimitScope.API_KEY, 2, Duration.ofMinutes(1), null);
        RateLimitPolicy orgLimit = policy(RateLimitScope.ORGANIZATION, 1000, Duration.ofMinutes(1),
                null);
        RateLimitService service = service();

        service.check(request(), List.of(keyLimit, orgLimit));
        service.check(request(), List.of(keyLimit, orgLimit));
        var third = service.check(request(), List.of(keyLimit, orgLimit));

        assertThat(third.allowed()).isFalse();
        assertThat(third.limit()).isEqualTo(2);
        assertThat(third.scope()).isEqualTo(RateLimitScope.API_KEY);
    }

    @Test
    void scopesAreIndependentCounters() {
        // One noisy key must not exhaust the organization's budget.
        RateLimitPolicy keyLimit = policy(RateLimitScope.API_KEY, 2, Duration.ofMinutes(1), null);
        RateLimitPolicy orgLimit = policy(RateLimitScope.ORGANIZATION, 5, Duration.ofMinutes(1),
                null);
        RateLimitService service = service();
        List<RateLimitPolicy> policies = List.of(keyLimit, orgLimit);

        service.check(request(), policies);
        service.check(request(), policies);
        // Third call exhausts the key, not the org.
        assertThat(service.check(request(), policies).scope()).isEqualTo(RateLimitScope.API_KEY);

        // A different key in the same org is still served.
        RateLimitRequest other = new RateLimitRequest(org, UUID.randomUUID(), project,
                environment, null, "/api/v1/payments", "203.0.113.10", null, T0);
        assertThat(service.check(other, policies).allowed()).isTrue();
    }

    @Test
    void twoTenantsNeverShareACounter() {
        // The critical isolation property. Without the organization in the key,
        // one tenant could exhaust another's limit.
        UUID otherOrg = UUID.randomUUID();
        RateLimitPolicy shared = policy(RateLimitScope.ORGANIZATION, 2, Duration.ofMinutes(1),
                null);
        RateLimitService service = service();

        service.check(request(), List.of(shared));
        service.check(request(), List.of(shared));
        assertThat(service.check(request(), List.of(shared)).allowed()).isFalse();

        RateLimitRequest otherTenant = new RateLimitRequest(otherOrg, UUID.randomUUID(), null,
                null, null, "/api/v1/payments", "198.51.100.1", null, T0);
        assertThat(service.check(otherTenant, List.of(shared)).allowed()).isTrue();
    }

    @Test
    void anonymousRequestsShareACounterRatherThanGettingFreshOnes() {
        // Otherwise an unauthenticated flood mints a new counter per request and is
        // never limited at all.
        RateLimitPolicy ipLimit = policy(RateLimitScope.IP, 2, Duration.ofMinutes(1), null);
        RateLimitService service = service();
        RateLimitRequest anonymous = new RateLimitRequest(org, null, null, null, null,
                "/api/v1/payments", "192.0.2.5", null, T0);

        service.check(anonymous, List.of(ipLimit));
        service.check(anonymous, List.of(ipLimit));

        assertThat(service.check(anonymous, List.of(ipLimit)).allowed()).isFalse();
    }

    @Test
    void differentAddressesHaveSeparateCounters() {
        RateLimitPolicy ipLimit = policy(RateLimitScope.IP, 1, Duration.ofMinutes(1), null);
        RateLimitService service = service();

        RateLimitRequest first = new RateLimitRequest(org, null, null, null, null, "/e",
                "192.0.2.5", null, T0);
        RateLimitRequest second = new RateLimitRequest(org, null, null, null, null, "/e",
                "192.0.2.6", null, T0);

        assertThat(service.check(first, List.of(ipLimit)).allowed()).isTrue();
        assertThat(service.check(first, List.of(ipLimit)).allowed()).isFalse();
        assertThat(service.check(second, List.of(ipLimit)).allowed()).isTrue();
    }

    @Test
    void anEndpointPolicyOnlyAppliesToMatchingEndpoints() {
        RateLimitPolicy endpointLimit = RateLimitPolicy.tokenBucket(UUID.randomUUID(), org,
                RateLimitScope.ENDPOINT, null, "/api/v1/payments", 1, Duration.ofMinutes(1), null);
        RateLimitService service = service();
        RateLimitRequest other = new RateLimitRequest(org, key, project, environment, null,
                "/api/v1/refunds", "203.0.113.10", null, T0);

        assertThat(service.check(other, List.of(endpointLimit)).allowed()).isTrue();
        // Different endpoint entirely: must not have been charged.
        assertThat(service.check(request(), List.of(endpointLimit)).allowed()).isTrue();
        assertThat(service.check(request(), List.of(endpointLimit)).allowed()).isFalse();
    }

    @Test
    void aPolicyFromAnotherOrganizationNeverApplies() {
        UUID otherOrg = UUID.randomUUID();
        RateLimitPolicy foreign = RateLimitPolicy.tokenBucket(UUID.randomUUID(), otherOrg,
                RateLimitScope.API_KEY, null, null, 1, Duration.ofMinutes(1), null);

        // Must be skipped, not merely refused: otherwise a foreign policy would
        // throttle this tenant.
        assertThat(service().check(request(), List.of(foreign)).isUnlimited()).isTrue();
    }

    @Test
    void aFailingStoreFailsClosed() {
        RateLimitCounterStore broken = new InMemoryRateLimitCounterStore() {
            @Override
            public boolean isAvailable() {
                return false;
            }
        };
        RateLimitService service = new RateLimitService(broken);
        RateLimitPolicy policy = policy(RateLimitScope.API_KEY, 1, Duration.ofMinutes(1), null);

        assertThatThrownBy(() -> service.check(request(), List.of(policy)))
                .isInstanceOf(RateLimitUnavailableException.class);
    }

    @Test
    void concurrentRequestsDoNotExceedTheLimit() throws Exception {
        // A read-then-write limiter would let many threads through at once and
        // admit far more than the configured limit. Only a concurrency test finds
        // this.
        int limit = 50;
        int threads = 200;
        RateLimitPolicy policy = policy(RateLimitScope.API_KEY, limit, Duration.ofMinutes(1),
                limit);
        RateLimitService service = service();
        AtomicInteger allowed = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(16);

        try {
            for (int i = 0; i < threads; i++) {
                pool.execute(() -> {
                    try {
                        start.await();
                        if (service.check(request(), List.of(policy)).allowed()) {
                            allowed.incrementAndGet();
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(allowed.get()).isLessThanOrEqualTo(limit);
    }
}
