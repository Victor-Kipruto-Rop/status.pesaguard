package com.pesaguard.backend.analytics.algorithm.anomaly;

import java.util.ArrayList;
import java.util.List;

/**
 * Sequential (online) change-point detectors: EWMA and CUSUM.
 *
 * <p>Unlike the batch detectors, these consume a series one observation at a time
 * and maintain only O(1) state, so they can run over an unbounded transaction
 * stream without ever holding the full history. That is the right tool for a
 * sustained shift - a merchant whose average ticket drifts upward over a day -
 * which a single-point z-score cannot see because each individual value still looks
 * ordinary against the recent window.
 *
 * <p>Both are stateful by construction. A detector instance is single-threaded and
 * must be confined to one stream; do not share an instance across tenants or
 * partition keys.
 */
public final class SequentialChangeDetector {

    private SequentialChangeDetector() {
    }

    /** Signal emitted as the detector consumes each new observation. */
    public record Signal(int index, double value, double statistic, boolean alarmed, String detail) {
    }

    /**
     * Exponentially Weighted Moving Average detector.
     *
     * <p>Tracks a weighted average where recent observations dominate. A value is
     * flagged when it deviates from the current EWMA by more than {@code lambda *
     * sigma} in absolute terms, where {@code sigma} is a running estimate of spread.
     * The smoothing factor {@code alpha} trades responsiveness against noise: a
     * larger alpha reacts faster but false-alarms more.
     *
     * <p>The EWMA is seeded with the first observation rather than zero, so the
     * first few points are not scored against an artificial baseline of 0.
     */
    public static final class Ewma {
        private final double alpha;
        private final double lambda;
        private double ewma;
        private double variance;
        private boolean initialised;
        private int count;

        public Ewma(double alpha, double lambda) {
            if (alpha <= 0.0 || alpha > 1.0) {
                throw new IllegalArgumentException("alpha must be in (0, 1]");
            }
            if (lambda <= 0.0) {
                throw new IllegalArgumentException("lambda must be positive");
            }
            this.alpha = alpha;
            this.lambda = lambda;
        }

        /** Feed one observation and report whether it breached the control limit. */
        public Signal observe(double value) {
            count++;
            if (!initialised) {
                ewma = value;
                variance = 0.0;
                initialised = true;
                return new Signal(count - 1, value, 0.0, false, "seeded");
            }
            double previous = ewma;
            ewma = alpha * value + (1.0 - alpha) * previous;
            // Track variance of the residual so the control limit adapts to spread.
            double residual = value - previous;
            variance = alpha * residual * residual + (1.0 - alpha) * variance;
            double sigma = Math.sqrt(Math.max(variance, 0.0));
            double deviation = Math.abs(value - ewma);
            boolean alarmed = sigma > 0.0 && deviation > lambda * sigma;
            return new Signal(count - 1, value, deviation, alarmed,
                    alarmed ? "ewma breach" : "ok");
        }

        public double currentMean() {
            return ewma;
        }

        public double currentSigma() {
            return Math.sqrt(Math.max(variance, 0.0));
        }
    }

    /**
     * Batch convenience wrapper: run EWMA over a whole series and return the alarms.
     *
     * <p>Provided for testing and for backfilling an alert history; the streaming
     * path should hold a single {@link Ewma} instance and call {@link Ewma#observe}.
     */
    public static List<Signal> ewmaScan(List<Double> values, double alpha, double lambda) {
        Ewma ewma = new Ewma(alpha, lambda);
        List<Signal> signals = new ArrayList<>();
        for (double v : values) {
            signals.add(ewma.observe(v));
        }
        return signals;
    }

    /**
     * Cumulative Sum (CUSUM) detector.
     *
     * <p>Accumulates deviations above and below a target into two one-sided
     * statistics, {@code S+} and {@code S-}, each reset to zero when it goes
     * negative. A sustained shift in one direction drives one of them past the
     * decision interval {@code threshold}; a single spike that reverts leaves both
     * near zero. This is the detector that catches slow, persistent drift which
     * point-in-time tests are blind to.
     *
     * <p>The allowance {@code k} (in units of {@code sigma}) is the slack absorbed
     * before accumulation begins. A larger k ignores small wobble and only reacts to
     * a shift bigger than routine noise.
     */
    public static final class Cusum {
        private final double target;
        private final double sigma;
        private final double allowance;
        private final double threshold;
        private double upperSum;
        private double lowerSum;
        private int count;

        public Cusum(double target, double sigma, double allowanceFactor, double threshold) {
            if (sigma <= 0.0) {
                throw new IllegalArgumentException("sigma must be positive");
            }
            this.target = target;
            this.sigma = sigma;
            this.allowance = allowanceFactor * sigma;
            this.threshold = threshold;
        }

        /** Feed one observation and report whether the cumulative sum breached. */
        public Signal observe(double value) {
            count++;
            double z = (value - target) / sigma;
            double k = allowance / sigma;
            upperSum = Math.max(0.0, upperSum + z - k);
            lowerSum = Math.max(0.0, lowerSum - z - k);
            boolean alarmed = upperSum > threshold || lowerSum > threshold;
            String detail = alarmed
                    ? (upperSum > threshold ? "upward shift" : "downward shift")
                    : "ok";
            return new Signal(count - 1, value, Math.max(upperSum, lowerSum), alarmed, detail);
        }

        public double upperCusum() {
            return upperSum;
        }

        public double lowerCusum() {
            return lowerSum;
        }

        /** Reset both accumulators after an acknowledged alarm or a deliberate reset. */
        public void reset() {
            upperSum = 0.0;
            lowerSum = 0.0;
        }
    }

    /** Batch convenience wrapper for CUSUM; see {@link #ewmaScan}. */
    public static List<Signal> cusumScan(List<Double> values, double target, double sigma,
                                         double allowanceFactor, double threshold) {
        Cusum cusum = new Cusum(target, sigma, allowanceFactor, threshold);
        List<Signal> signals = new ArrayList<>();
        for (double v : values) {
            signals.add(cusum.observe(v));
        }
        return signals;
    }
}
