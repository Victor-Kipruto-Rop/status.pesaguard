package com.pesaguard.backend.analytics.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.pesaguard.backend.analytics.domain.ApiRequestEvent;

/**
 * The request-thread safety properties of the buffer.
 *
 * <p>These matter more than they look: this class sits on the request path of a
 * payment API. The properties asserted here are the difference between an
 * analytics problem and an availability incident.
 */
class UsageEventRecorderTest {

    private static final Instant T = Instant.parse("2026-03-15T14:37:52Z");

    private ApiRequestEvent event(String requestId) {
        return ApiRequestEvent.record(requestId, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), null, "/api/v1/payments", "POST",
                200, 25, 512L, T, null);
    }

    @Test
    void recordedEventsReachTheSink() throws Exception {
        List<ApiRequestEvent> written = new CopyOnWriteArrayList<>();
        CountDownLatch delivered = new CountDownLatch(1);
        try (UsageEventRecorder recorder = new UsageEventRecorder(100, event -> {
            written.add(event);
            delivered.countDown();
        })) {
            assertThat(recorder.record(event("r1"))).isTrue();

            assertThat(delivered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(written).hasSize(1);
            assertThat(written.get(0).getRequestId()).isEqualTo("r1");
        }
    }

    @Test
    void recordingNeverBlocksWhenTheBufferIsFull() {
        // A sink that never returns, so the writer cannot drain and the buffer
        // fills. record() must still return promptly: it runs on the request
        // thread of a live payment API.
        try (UsageEventRecorder recorder = new UsageEventRecorder(4, event -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        })) {
            long startedAt = System.nanoTime();
            for (int index = 0; index < 500; index++) {
                recorder.record(event("r" + index));
            }
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

            // 500 enqueues must not take anywhere near the 50ms per-item sink.
            assertThat(elapsedMs).isLessThan(2_000);
            assertThat(recorder.droppedCount()).isPositive();
        }
    }

    @Test
    void overflowDropsRatherThanGrowingWithoutBound() {
        // An unbounded queue would exhaust the heap under sustained overload.
        // Capacity is a hard ceiling.
        try (UsageEventRecorder recorder = new UsageEventRecorder(8, event -> {
            try {
                Thread.sleep(20);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        })) {
            for (int index = 0; index < 200; index++) {
                recorder.record(event("r" + index));
            }

            assertThat(recorder.queueDepth()).isLessThanOrEqualTo(8);
            // Dropped, never silently discarded.
            assertThat(recorder.acceptedCount() + recorder.droppedCount()).isEqualTo(200);
        }
    }

    @Test
    void aFailingSinkDoesNotKillTheWriter() throws Exception {
        CountDownLatch seen = new CountDownLatch(2);
        List<String> ids = new CopyOnWriteArrayList<>();
        try (UsageEventRecorder recorder = new UsageEventRecorder(100, event -> {
            // Record first, then signal. The other order counts the latch down
            // while the write is still in flight, so the assertion below can run
            // against a list that is one element short -- which is how this test
            // failed intermittently under full-suite load and passed in isolation.
            ids.add(event.getRequestId());
            seen.countDown();
            throw new IllegalStateException("database unavailable");
        })) {
            recorder.record(event("r1"));
            recorder.record(event("r2"));

            assertThat(seen.await(5, TimeUnit.SECONDS)).isTrue();
            // Both were attempted, despite the first throwing.
            assertThat(ids).containsExactlyInAnyOrder("r1", "r2");
            assertThat(recorder.droppedCount()).isPositive();
        }
    }

    @Test
    void aNullEventIsIgnored() {
        try (UsageEventRecorder recorder = new UsageEventRecorder(10, event -> { })) {
            assertThat(recorder.record(null)).isFalse();
            assertThat(recorder.acceptedCount()).isZero();
        }
    }

    @Test
    void shutdownIsCleanAndIdempotent() {
        UsageEventRecorder recorder = new UsageEventRecorder(10, event -> { });
        recorder.destroy();
        // Must not throw; Spring calls destroy during shutdown.
        recorder.destroy();
    }
}