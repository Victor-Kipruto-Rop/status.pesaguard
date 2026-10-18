package com.pesaguard.backend.analytics.algorithm.alerting;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Threshold alerting with hysteresis, deduplication, grouping, and suppression.
 *
 * <p>A naive alert fires every time a metric crosses a threshold. On a noisy signal
 * that hovers right at the line it fires on and off repeatedly - "flapping" - and the
 * resulting storm trains operators to ignore alerts. Hysteresis fixes this with two
 * thresholds: the alert turns ON when the metric rises above the high threshold and
 * only turns OFF when it falls below the low threshold. The gap between them is a
 * dead-band the signal must cross in each direction, so a value oscillating inside the
 * band produces no transitions at all.
 *
 * <p>On top of that, identical alerts are deduplicated within a window so a condition
 * that persists does not re-fire every evaluation; they are grouped by a key so many
 * related events (every account in one ring) surface as one grouped alert rather than
 * dozens of near-identical pages; and suppression mutes alerts whose group is already
 * firing, so a single underlying cause does not page once per symptom.
 *
 * <p>State (on/off per metric, last-fired time per group) is retained between
 * evaluations because the whole point of hysteresis and suppression is memory across
 * time. Time is passed in, never read from a clock, so behaviour is deterministic and
 * testable.
 */
public final class AlertManager {

    private AlertManager() {
    }

    /** A metric observation to evaluate. */
    public record Metric(String metricKey, String groupKey, double value, long nowMillis) {
    }

    /** An alert that should be raised to the operator. */
    public record Alert(String metricKey, String groupKey, double value, long firedAtMillis) {
    }

    /** The outcome of evaluating one metric. */
    public record Evaluation(boolean fired, boolean suppressed, String reason) {
    }

    /** Per-metric hysteresis configuration. */
    public record Threshold(double high, double low, long dedupWindowMillis) {
    }

    /** Per-metric on/off state, persisted across evaluations. */
    private static final class State {
        boolean on;
        long lastFiredMillis;
    }

    /** Last time each group fired, used for suppression. */
    private final Map<String, Long> lastGroupFireMillis = new LinkedHashMap<>();

    /** Per-metric hysteresis state. */
    private final Map<String, State> states = new LinkedHashMap<>();

    /** Per-group suppression window: a new alert in a recently-fired group is muted. */
    private long suppressionWindowMillis;

    /** Configure how long a group stays suppressed after it fires. */
    public AlertManager withSuppressionWindow(long windowMillis) {
        this.suppressionWindowMillis = windowMillis;
        return this;
    }

    /**
     * Evaluate one metric and decide whether to raise an alert.
     *
     * <p>Applies hysteresis first (does this value trip the on/off latch?), then
     * deduplication (has this exact alert fired too recently?), then suppression (is
     * its group already firing?). The order matters: hysteresis prevents flapping,
     * dedup prevents re-paging the same condition, and suppression collapses
     * correlated symptoms into their group.
     *
     * @return an alert to raise, or {@code null} when nothing should fire
     */
    public Alert evaluate(Metric metric, Threshold threshold) {
        State state = states.computeIfAbsent(metric.metricKey(), k -> new State());
        boolean crossedHigh = metric.value() >= threshold.high();
        boolean crossedLow = metric.value() <= threshold.low();

        // Hysteresis latch: only turn on above high, only turn off below low.
        if (!state.on && crossedHigh) {
            state.on = true;
        } else if (state.on && crossedLow) {
            state.on = false;
        }
        if (!state.on) {
            // Either never fired, or turned off; either way no alert now.
            return null;
        }

        // Deduplication: suppress a repeat of the same alert inside its window.
        if (metric.nowMillis() - state.lastFiredMillis < threshold.dedupWindowMillis()) {
            return null;
        }
        state.lastFiredMillis = metric.nowMillis();

        // Group suppression: mute if the group already fired very recently.
        Long lastGroupFire = lastGroupFireMillis.get(metric.groupKey());
        if (lastGroupFire != null
                && metric.nowMillis() - lastGroupFire < suppressionWindowMillis) {
            return null;
        }
        lastGroupFireMillis.put(metric.groupKey(), metric.nowMillis());
        return new Alert(metric.metricKey(), metric.groupKey(), metric.value(), metric.nowMillis());
    }

    /** Batch convenience: evaluate a list of metrics and return the alerts that fired. */
    public List<Alert> evaluateAll(List<Metric> metrics, Map<String, Threshold> thresholds) {
        List<Alert> alerts = new ArrayList<>();
        for (Metric metric : metrics) {
            Threshold threshold = thresholds.get(metric.metricKey());
            if (threshold == null) {
                continue;
            }
            Alert alert = evaluate(metric, threshold);
            if (alert != null) {
                alerts.add(alert);
            }
        }
        return alerts;
    }

    /** Whether a metric is currently in the ON state, for observability and tests. */
    public boolean isOn(String metricKey) {
        State state = states.get(metricKey);
        return state != null && state.on;
    }
}
