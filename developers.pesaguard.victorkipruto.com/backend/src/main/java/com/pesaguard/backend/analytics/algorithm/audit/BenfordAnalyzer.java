package com.pesaguard.backend.analytics.algorithm.audit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Benford's Law (first-digit law) analyzer for bulk-data audits.
 *
 * <p>Benford's Law predicts the frequency distribution of leading digits in
 * naturally occurring numeric data: the digit 1 appears about 30.1% of the time,
 * the digit 9 about 4.6%. A batch of transaction amounts that materially departs
 * from this distribution is a signal that the data was manufactured, manually
 * tampered with, or drawn from a bounded/synthetic set rather than from real
 * economic activity.
 *
 * <p>This is a screening tool, not a verdict. It raises a flag that warrants human
 * review; it never concludes fraud on its own, consistent with the platform's rule
 * that anomaly, suspicion, and confirmed fraud are distinct states.
 */
public final class BenfordAnalyzer {

    /** Expected leading-digit frequencies under Benford's Law for digits 1-9. */
    private static final double[] BENFORD = {
            0.0, // index 0 unused
            0.30103, 0.17609, 0.12494, 0.09691, 0.07918,
            0.06695, 0.05799, 0.05115, 0.04576
    };

    private BenfordAnalyzer() {
    }

    /**
     * Result of a Benford analysis: the mean absolute deviation from the expected
     * distribution, the chi-square statistic, and the per-digit observed counts.
     */
    public record BenfordResult(
            double meanAbsoluteDeviation,
            double chiSquare,
            int sampleSize,
            Map<Integer, Long> leadingDigitCounts,
            double worstDigitDeviation,
            int worstDigit) {
    }

    /**
     * Analyse the leading-digit distribution of a batch of positive amounts.
     *
     * <p>Values that are zero, negative, or have no non-zero leading digit are
     * skipped rather than coerced, because forcing them into a bucket would
     * manufacture a deviation that does not exist.
     *
     * @param amounts the numeric values to audit (typically transaction amounts)
     * @return the computed statistics, or a zeroed result when there is too little
     *         data to test
     */
    public static BenfordResult analyze(List<Double> amounts) {
        long[] counts = new long[10];
        int sampleSize = 0;
        for (double amount : amounts) {
            int digit = leadingDigit(amount);
            if (digit >= 1 && digit <= 9) {
                counts[digit]++;
                sampleSize++;
            }
        }
        if (sampleSize == 0) {
            return new BenfordResult(0.0, 0.0, 0, Map.of(), 0.0, 0);
        }

        double mad = 0.0;
        double chiSquare = 0.0;
        double worstDeviation = 0.0;
        int worstDigit = 0;
        Map<Integer, Long> digitCounts = new LinkedHashMap<>();
        for (int d = 1; d <= 9; d++) {
            long observed = counts[d];
            double observedFreq = (double) observed / sampleSize;
            double deviation = Math.abs(observedFreq - BENFORD[d]);
            mad += deviation;
            double expected = BENFORD[d] * sampleSize;
            chiSquare += (observed - expected) * (observed - expected) / expected;
            if (deviation > worstDeviation) {
                worstDeviation = deviation;
                worstDigit = d;
            }
            digitCounts.put(d, observed);
        }
        mad /= 9.0;
        return new BenfordResult(mad, chiSquare, sampleSize, digitCounts, worstDeviation, worstDigit);
    }

    /**
     * Extract the leading significant digit of a value.
     *
     * <p>Uses absolute value and strips the integer part of the logarithm to locate
     * the most significant digit, which is stable across magnitudes (e.g. 0.0045 and
     * 4500 both yield 4).
     */
    static int leadingDigit(double amount) {
        if (amount <= 0.0 || Double.isNaN(amount) || Double.isInfinite(amount)) {
            return -1;
        }
        double magnitude = Math.floor(Math.log10(amount));
        // Normalise to a value in [1, 10).
        double normalised = amount / Math.pow(10.0, magnitude);
        // Guard against floating-point landing on 9.999999 for an exact power of ten.
        if (normalised >= 10.0) {
            normalised /= 10.0;
        }
        return (int) Math.floor(normalised);
    }

    /**
     * Convenience helper: leading digits of a batch, in order, for callers that
     * only need the raw sequence (e.g. to render a histogram).
     */
    public static List<Integer> leadingDigits(List<Double> amounts) {
        List<Integer> digits = new ArrayList<>();
        for (double amount : amounts) {
            int digit = leadingDigit(amount);
            if (digit >= 1 && digit <= 9) {
                digits.add(digit);
            }
        }
        return digits;
    }
}
