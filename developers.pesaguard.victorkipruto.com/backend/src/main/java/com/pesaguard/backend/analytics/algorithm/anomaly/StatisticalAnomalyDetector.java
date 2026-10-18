package com.pesaguard.backend.analytics.algorithm.anomaly;

import java.util.ArrayList;
import java.util.List;

import com.pesaguard.backend.analytics.algorithm.statistics.DescriptiveStatistics;

/**
 * Threshold-based statistical anomaly detectors: z-score, MAD, and IQR.
 *
 * <p>All three answer the same question - "is this value far from the recent
 * baseline?" - but they use different notions of "far", and the difference matters
 * financially. The z-score uses mean and standard deviation and is the most
 * sensitive to a single very large transaction; MAD and IQR use robust estimators
 * and ignore that leverage. A detector that flags the outlier it is meant to catch
 * (because the outlier corrupted its own baseline) is useless, so the robust
 * variants are the ones the scoring service trusts for decisions.
 */
public final class StatisticalAnomalyDetector {

    private StatisticalAnomalyDetector() {
    }

    /** The kind of statistical test that produced a score. */
    public enum Test {
        Z_SCORE,
        MODIFIED_Z_SCORE,
        IQR_FENCE
    }

    /** One detected anomaly: which value, which test, and how far past the fence. */
    public record Anomaly(int index, double value, Test test, double score, double threshold) {
    }

    /** Everything a caller needs to act on (or audit) a batch scan. */
    public record ScanResult(List<Anomaly> anomalies, int sampleSize, double mean, double median, double stdDev) {
    }

    /**
     * Flag values whose absolute z-score exceeds {@code threshold}.
     *
     * <p>Least robust of the three: a single extreme value inflates the standard
     * deviation and can mask a second, equally extreme value. Provided because it
     * is the familiar baseline, not because it is the recommended decision input.
     */
    public static ScanResult zScoreScan(List<Double> values, double threshold) {
        requireAtLeastTwo(values);
        double mean = DescriptiveStatistics.mean(values);
        double median = DescriptiveStatistics.median(values);
        double stdDev = DescriptiveStatistics.stdDev(values);
        List<Anomaly> anomalies = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            double score = Math.abs(DescriptiveStatistics.zScore(values, values.get(i)));
            if (stdDev > 0.0 && score > threshold) {
                anomalies.add(new Anomaly(i, values.get(i), Test.Z_SCORE, score, threshold));
            }
        }
        return new ScanResult(anomalies, values.size(), mean, median, stdDev);
    }

    /**
     * Flag values whose absolute modified z-score exceeds {@code threshold}.
     *
     * <p>The recommended detector for transaction amounts. A threshold of 3.5 is
     * the conventional choice and maps to the "3.5 sigma" rule; a modified z-score
     * beyond 3.5 means the value is far outside the bulk of the distribution even
     * after the distribution itself is allowed to be contaminated.
     */
    public static ScanResult modifiedZScoreScan(List<Double> values, double threshold) {
        requireAtLeastTwo(values);
        double mean = DescriptiveStatistics.mean(values);
        double median = DescriptiveStatistics.median(values);
        double stdDev = DescriptiveStatistics.stdDev(values);
        List<Anomaly> anomalies = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            double score = Math.abs(DescriptiveStatistics.modifiedZScore(values, values.get(i)));
            if (score > threshold) {
                anomalies.add(new Anomaly(i, values.get(i), Test.MODIFIED_Z_SCORE, score, threshold));
            }
        }
        return new ScanResult(anomalies, values.size(), mean, median, stdDev);
    }

    /**
     * Flag values outside the Tukey fences {@code [Q1 - k*IQR, Q3 + k*IQR]}.
     *
     * <p>{@code k=1.5} marks "outliers"; {@code k=3.0} marks "far out". The fences
     * are derived purely from quartiles, so they are completely insensitive to the
     * magnitude of any single point. This is the detector to use when a few very
     * large legitimate transactions must not widen the acceptance region.
     */
    public static ScanResult iqrScan(List<Double> values, double k) {
        requireAtLeastTwo(values);
        double q1 = DescriptiveStatistics.percentile(values, 25.0);
        double q3 = DescriptiveStatistics.percentile(values, 75.0);
        double iqr = q3 - q1;
        double lower = q1 - k * iqr;
        double upper = q3 + k * iqr;
        double mean = DescriptiveStatistics.mean(values);
        double median = DescriptiveStatistics.median(values);
        double stdDev = DescriptiveStatistics.stdDev(values);
        List<Anomaly> anomalies = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            double v = values.get(i);
            if (v < lower || v > upper) {
                // Distance past the nearer fence, normalised by IQR so the score is
                // comparable across batches with different absolute scales.
                double distance = v < lower ? (lower - v) : (v - upper);
                double score = iqr == 0.0 ? Double.MAX_VALUE : distance / iqr;
                anomalies.add(new Anomaly(i, v, Test.IQR_FENCE, score, k));
            }
        }
        return new ScanResult(anomalies, values.size(), mean, median, stdDev);
    }

    private static void requireAtLeastTwo(List<Double> values) {
        if (values == null || values.size() < 2) {
            throw new IllegalArgumentException("statistical scan requires at least two values");
        }
    }
}
