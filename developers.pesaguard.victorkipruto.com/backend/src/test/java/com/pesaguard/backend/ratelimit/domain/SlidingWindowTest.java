package com.pesaguard.backend.ratelimit.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Sliding window behaviour.
 *
 * <p>The case that distinguishes this from a fixed window is the boundary
 * exploit, asserted directly: a fixed window would let a caller spend its whole
 * allowance twice within a few seconds either side of the boundary.
 */
class SlidingWindowTest {

    private static final long T0 = 1_700_000_000_000L;
    private static final long WINDOW = 60_000L;

    @Test
    void countsOnlyRequestsInsideTheWindow() {
        long[] timestamps = { T0, T0 + 1_000, T0 + 2_000 };

        assertThat(SlidingWindow.countWithin(timestamps, T0 + 2_000, WINDOW)).isEqualTo(3);
        // 30s later the first is still in, none have aged out yet.
        assertThat(SlidingWindow.countWithin(timestamps, T0 + 32_000, WINDOW)).isEqualTo(3);
        // Past the window everything is gone.
        assertThat(SlidingWindow.countWithin(timestamps, T0 + 62_000, WINDOW)).isZero();
    }

    @Test
    void aFixedWindowBoundaryExploitIsNotPossible() {
        // With a limit of 5, spend all 5 just before a 60s boundary.
        long[] spent = new long[5];
        for (int i = 0; i < 5; i++) {
            spent = SlidingWindow.append(spent, T0 + 59_000 + i, WINDOW);
        }
        assertThat(SlidingWindow.countWithin(spent, T0 + 59_004, WINDOW)).isEqualTo(5);

        // Immediately after the boundary a fixed window would report 0 used and
        // allow 5 more, admitting 10 in two seconds. The sliding window does not.
        assertThat(SlidingWindow.countWithin(spent, T0 + 60_001, WINDOW)).isEqualTo(5);
    }

    @Test
    void theWindowBoundaryIsExclusive() {
        // A timestamp exactly at the cutoff has aged out, so it belongs to exactly
        // one window and cannot be counted twice across adjacent windows.
        long[] timestamps = { T0 };

        assertThat(SlidingWindow.countWithin(timestamps, T0 + WINDOW, WINDOW)).isZero();
        assertThat(SlidingWindow.countWithin(timestamps, T0 + WINDOW - 1, WINDOW)).isEqualTo(1);
    }

    @Test
    void appendPrunesExpiredEntries() {
        long[] timestamps = { T0, T0 + 1_000 };

        long[] appended = SlidingWindow.append(timestamps, T0 + 90_000, WINDOW);

        // Both old entries pruned, only the new one kept. Without this an untouched
        // counter would grow without bound.
        assertThat(appended).hasSize(1);
        assertThat(appended[0]).isEqualTo(T0 + 90_000);
    }

    @Test
    void prunedRemovesExpiredWithoutRecording() {
        long[] timestamps = { T0, T0 + 80_000 };

        long[] pruned = SlidingWindow.pruned(timestamps, T0 + 90_000, WINDOW);

        assertThat(pruned).hasSize(1);
        assertThat(pruned[0]).isEqualTo(T0 + 80_000);
    }

    @Test
    void prunedReturnsTheSameArrayWhenNothingExpired() {
        long[] timestamps = { T0 + 80_000, T0 + 85_000 };

        assertThat(SlidingWindow.pruned(timestamps, T0 + 90_000, WINDOW)).isSameAs(timestamps);
    }

    @Test
    void retryAfterWaitsForTheOldestRequestToAgeOut() {
        // Not the whole window: the wait is exactly until the first slot frees,
        // so a client is not told to wait longer than necessary.
        long[] timestamps = { T0 + 10_000, T0 + 20_000 };

        long retry = SlidingWindow.retryAfterMillis(timestamps, T0 + 30_000, WINDOW);

        assertThat(retry).isEqualTo(40_000L);
    }

    @Test
    void retryAfterIsZeroWhenTheWindowIsClear() {
        assertThat(SlidingWindow.retryAfterMillis(new long[0], T0, WINDOW)).isZero();
    }

    @Test
    void retryAfterIsAtLeastOneMillisecond() {
        // A client told to retry after 0ms is simply refused again.
        long[] timestamps = { T0 + WINDOW - 1 };

        assertThat(SlidingWindow.retryAfterMillis(timestamps, T0 + WINDOW, WINDOW))
                .isGreaterThanOrEqualTo(1L);
    }

    @Test
    void emptyAndNullInputsAreHandled() {
        assertThat(SlidingWindow.countWithin(null, T0, WINDOW)).isZero();
        assertThat(SlidingWindow.countWithin(new long[0], T0, WINDOW)).isZero();
        assertThat(SlidingWindow.oldestWithin(null, T0, WINDOW)).isEmpty();
        assertThat(SlidingWindow.pruned(null, T0, WINDOW)).isEmpty();
    }
}