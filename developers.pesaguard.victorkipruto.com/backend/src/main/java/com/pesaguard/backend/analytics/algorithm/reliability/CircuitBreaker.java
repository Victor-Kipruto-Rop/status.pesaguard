package com.pesaguard.backend.analytics.algorithm.reliability;

/**
 * Circuit breaker for external dependencies.
 *
 * <p>A circuit breaker stops a system from hammering a dependency that has already
 * shown it is failing. It has three states. In CLOSED, calls pass through and each
 * failure is counted. When consecutive failures reach a threshold, it trips to OPEN
 * and rejects calls immediately without touching the dependency - a fast failure that
 * is far better for the caller than a long timeout. After a cooldown it moves to
 * HALF_OPEN and allows a small number of trial calls: if they succeed it closes again,
 * and if they fail it re-opens. This shape lets a recovering dependency heal without
 * being buried under a full load of retries the instant it comes back.
 *
 * <p>The breaker counts consecutive failures, not total failures. A single success
 * resets the counter, because a dependency that alternates success and failure is
 * degraded but not down, and tripping on it would be an over-reaction that blocks
 * otherwise-good traffic.
 *
 * <p>The half-open trial budget is a deliberate safety limit: only {@code
 * halfOpenMaxCalls} calls are permitted in HALF_OPEN so a flapping dependency cannot
 * be flooded by every waiting caller at once the moment the cooldown elapses.
 *
 * <p>This is a pure state machine; it does no I/O. The caller invokes
 * {@link #allowRequest} before a call and reports the outcome via {@link #recordSuccess}
 * or {@link #recordFailure}. Time is passed in rather than read from a clock, so the
 * behaviour is fully testable.
 */
public final class CircuitBreaker {

    /** Lifecycle states of the breaker. */
    public enum State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    private final int failureThreshold;
    private final long openDurationMillis;
    private final int halfOpenMaxCalls;
    private final CircuitBreakerListener listener;

    private State state = State.CLOSED;
    private int consecutiveFailures;
    private int halfOpenCalls;
    private long openedAtMillis;

    /**
     * Optional listener invoked on every state transition, so the service layer can
     * emit metrics and alerts when a breaker opens or closes. May be null.
     */
    public interface CircuitBreakerListener {
        void onStateChange(State from, State to);
    }

    /**
     * @param failureThreshold  consecutive failures that trip the breaker to OPEN
     * @param openDurationMillis cooldown before a HALF_OPEN trial is allowed
     * @param halfOpenMaxCalls  trial calls permitted in HALF_OPEN before a decision
     * @param listener          transition callback, or null
     */
    public CircuitBreaker(int failureThreshold, long openDurationMillis, int halfOpenMaxCalls,
                          CircuitBreakerListener listener) {
        if (failureThreshold < 1) {
            throw new IllegalArgumentException("failureThreshold must be at least 1");
        }
        if (openDurationMillis <= 0) {
            throw new IllegalArgumentException("openDurationMillis must be positive");
        }
        if (halfOpenMaxCalls < 1) {
            throw new IllegalArgumentException("halfOpenMaxCalls must be at least 1");
        }
        this.failureThreshold = failureThreshold;
        this.openDurationMillis = openDurationMillis;
        this.halfOpenMaxCalls = halfOpenMaxCalls;
        this.listener = listener;
    }

    /**
     * Decide whether a call may proceed, advancing state if the cooldown has elapsed.
     *
     * <p>In CLOSED this always allows. In OPEN it allows only after
     * {@code openDurationMillis} has passed, at which point it transitions to
     * HALF_OPEN and grants a trial budget. In HALF_OPEN it allows until the trial
     * budget is exhausted.
     */
    public boolean allowRequest(long nowMillis) {
        switch (state) {
            case CLOSED -> {
                return true;
            }
            case OPEN -> {
                if (nowMillis - openedAtMillis >= openDurationMillis) {
                    transition(State.HALF_OPEN);
                    halfOpenCalls = 0;
                    // Fall through to grant the first half-open trial below.
                } else {
                    return false;
                }
            }
            case HALF_OPEN -> {
                // handled below
            }
        }
        return halfOpenCalls < halfOpenMaxCalls;
    }

    /** Record a successful call; resets failure state and closes the breaker. */
    public void recordSuccess() {
        consecutiveFailures = 0;
        if (state == State.HALF_OPEN) {
            halfOpenCalls++;
            transition(State.CLOSED);
        }
    }

    /** Record a failed call; trips the breaker once the failure threshold is reached. */
    public void recordFailure(long nowMillis) {
        consecutiveFailures++;
        if (state == State.HALF_OPEN) {
            halfOpenCalls++;
            // A failed trial re-opens immediately; the dependency has not recovered.
            openedAtMillis = nowMillis;
            transition(State.OPEN);
            return;
        }
        if (state == State.CLOSED && consecutiveFailures >= failureThreshold) {
            openedAtMillis = nowMillis;
            transition(State.OPEN);
        }
    }

    public State state() {
        return state;
    }

    public int consecutiveFailures() {
        return consecutiveFailures;
    }

    private void transition(State to) {
        if (state != to) {
            State from = state;
            state = to;
            if (listener != null) {
                listener.onStateChange(from, to);
            }
        }
    }
}
