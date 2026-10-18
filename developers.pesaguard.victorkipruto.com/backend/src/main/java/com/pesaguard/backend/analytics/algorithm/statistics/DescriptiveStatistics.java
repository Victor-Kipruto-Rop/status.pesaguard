package com.pesaguard.backend.analytics.algorithm.statistics;

import java.util.List;

/**
 * Core descriptive-statistics helpers used by the anomaly detectors.
 *
 * <p>Every method here is a pure function over an immutable input list. Nothing in
 * this class touches Spring, the database, or a clock, so the numbers it produces
 * are deterministic and can be unit-tested against hand-computed fixtures. That
 * matters for financial anomaly detection: an unexplained statistical surprise is
 * far worse than a missing column.
 *
 * <p>The distributions used downstream (z-score, MAD, IQR) are deliberately the
 * robust ones. Mean and standard deviation collapse under a single large outlier,
 * which is exactly the case an anomaly detector is meant to surface. Median and
 * MAD are chosen because they are resistant to the very events we are hunting.
 */
public final class DescriptiveStatistics {

    private DescriptiveStatistics() {
    }

    /** Arithmetic mean. Susceptible to outliers; callers should prefer {@link #median}. */
    public static double mean(List<Double> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("values must not be empty");
        }
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
    }

    /** Median (50th percentile). Robust to outliers. */
    public static double median(List<Double> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("values must not be empty");
        }
        return percentile(values, 50.0);
    }

    /**
     * Population standard deviation.
     *
     * <p>Divides by {@code n}, not {@code n-1}. The series passed in is the full
     * observed population, not a sample drawn from a larger one, so the population
     * form is the correct estimator of the spread we actually see.
     */
    public static double stdDev(List<Double> values) {
        if (values == null || values.size() < 2) {
            throw new IllegalArgumentException("need at least two values for standard deviation");
        }
        double m = mean(values);
        double sumSq = 0.0;
        for (double v : values) {
            double d = v - m;
            sumSq += d * d;
        }
        return Math.sqrt(sumSq / values.size());
    }

    /**
     * Median Absolute Deviation: {@code median(|x_i - median(x)|)}.
     *
     * <p>Robust estimate of spread. Scaled by the constant 1.4826 it becomes a
     * consistent estimator of the standard deviation for normally distributed data
     * while remaining unaffected by a handful of extreme values.
     */
    public static double mad(List<Double> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("values must not be empty");
        }
        double med = median(values);
        List<Double> absDeviations = values.stream().map(v -> Math.abs(v - med)).toList();
        return median(absDeviations);
    }

    /** Interquartile range: {@code Q3 - Q1}. Robust to outliers. */
    public static double iqr(List<Double> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("values must not be empty");
        }
        return percentile(values, 75.0) - percentile(values, 25.0);
    }

    /**
     * Linear-interpolated percentile using the {@code (n-1)} rank convention.
     *
     * <p>Chosen over the nearest-rank method so that a percentile of a small series
     * is not snapped to an observed sample, which would make the 25th/75th fences
     * insensitive to the data between observations.
     *
     * @param p percentile in {@code [0, 100]}
     */
    public static double percentile(List<Double> values, double p) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("values must not be empty");
        }
        if (p < 0.0 || p > 100.0) {
            throw new IllegalArgumentException("percentile must be in [0, 100]");
        }
        List<Double> sorted = values.stream().sorted().toList();
        if (sorted.size() == 1) {
            return sorted.get(0);
        }
        double rank = (p / 100.0) * (sorted.size() - 1);
        int low = (int) Math.floor(rank);
        int high = (int) Math.ceil(rank);
        if (low == high) {
            return sorted.get(low);
        }
        double frac = rank - low;
        return sorted.get(low) + frac * (sorted.get(high) - sorted.get(low));
    }

    /**
     * Z-score of {@code value} against the series: {@code (value - mean) / stdDev}.
     *
     * <p>Returns {@code 0.0} when the series has no spread, because a flat series
     * makes the z-score undefined and callers use a non-zero result as an alarm
     * signal, not as a division-by-zero crash.
     */
    public static double zScore(List<Double> values, double value) {
        double sd = stdDev(values);
        if (sd == 0.0) {
            return 0.0;
        }
        return (value - mean(values)) / sd;
    }

    /**
     * Modified (robust) z-score using MAD: {@code 0.6745 * (x - median) / MAD}.
     *
     * <p>The 0.6745 factor rescales MAD to be comparable to a standard deviation
     * for normal data. This is the score used for outlier decisions because, unlike
     * the plain z-score, it does not let the outliers it is looking for corrupt the
     * baseline it measures against.
     */
    public static double modifiedZScore(List<Double> values, double value) {
        double med = median(values);
        double mad = mad(values);
        if (mad == 0.0) {
            return 0.0;
        }
        return 0.6745 * (value - med) / mad;
    }
}
