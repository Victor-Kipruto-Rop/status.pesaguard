package com.pesaguard.backend.analytics.algorithm.seasonality;

import java.util.ArrayList;
import java.util.List;

import com.pesaguard.backend.analytics.algorithm.statistics.DescriptiveStatistics;

/**
 * Seasonality-aware baselines via STL-style decomposition.
 *
 * <p>A transaction series is rarely flat. Mobile-money volume rises and falls on a
 * daily and weekly rhythm: mornings are quiet, evenings spike, weekends differ from
 * weekdays, month-end salaries arrive. A baseline that ignores this rhythm will call
 * every evening peak an anomaly and every morning trough a collapse - a flood of
 * false positives that buries the genuine events. The point of decomposition is to
 * separate the predictable seasonal shape from the leftover, so only the leftover is
 * judged for anomalies.
 *
 * <p>This performs a classical decomposition into three additive components:
 * <ul>
 *   <li><b>trend</b> - a centred moving average capturing the slow drift;</li>
 *   <li><b>seasonal</b> - the repeating per-period shape, estimated as the mean
 *       residual per position within the period and re-centred to sum to zero;</li>
 *   <li><b>remainder</b> - what is left, which is what anomaly detection runs
 *       on.</li>
 * </ul>
 *
 * <p>An additive model is used rather than multiplicative because transaction counts
 * and settlement amounts are non-negative and an additive split cannot synthesise a
 * negative baseline. The seasonal index is forced to sum to zero so that the three
 * components always reconstruct the original series exactly - a decomposition that did
 * not add back up to the input could not be trusted for reconciliation, since a
 * discrepancy would be indistinguishable from a real mismatch.
 */
public final class SeasonalDecomposition {

    private SeasonalDecomposition() {
    }

    /** The three additive components plus the deseasonalised series. */
    public record Decomposition(
            List<Double> trend,
            List<Double> seasonal,
            List<Double> remainder,
            List<Double> deseasonalised,
            int period) {
    }

    /**
     * Decompose a series with a known seasonal period.
     *
     * @param series the observed values, in time order
     * @param period the seasonal cycle length in samples (e.g. 24 for hourly data with
     *               a daily cycle, 7 for daily data with a weekly cycle)
     * @return the trend, seasonal, and remainder components
     */
    public static Decomposition decompose(List<Double> series, int period) {
        if (series == null || series.isEmpty()) {
            throw new IllegalArgumentException("series must not be empty");
        }
        if (period < 2) {
            throw new IllegalArgumentException("period must be at least 2");
        }
        int n = series.size();
        // Trend via a centred moving average. An even period is centred across the
        // two middle samples; an odd period uses a single middle sample.
        List<Double> trend = movingAverage(series, period);

        // Detrended series: observed minus trend, where a trend value exists.
        List<Double> detrended = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            detrended.add(trend.get(i) == null ? Double.NaN : series.get(i) - trend.get(i));
        }

        // Seasonal index: mean detrended value for each position within the period,
        // then re-centred so the period sums to zero.
        double[] seasonalIndex = new double[period];
        int[] counts = new int[period];
        for (int i = 0; i < n; i++) {
            double value = detrended.get(i);
            if (Double.isNaN(value)) {
                continue;
            }
            seasonalIndex[i % period] += value;
            counts[i % period]++;
        }
        for (int p = 0; p < period; p++) {
            seasonalIndex[p] = counts[p] == 0 ? 0.0 : seasonalIndex[p] / counts[p];
        }
        double indexMean = DescriptiveStatistics.mean(java.util.Arrays.stream(seasonalIndex).boxed().toList());
        for (int p = 0; p < period; p++) {
            seasonalIndex[p] -= indexMean;
        }

        List<Double> seasonal = new ArrayList<>(n);
        List<Double> remainder = new ArrayList<>(n);
        List<Double> deseasonalised = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            double s = seasonalIndex[i % period];
            seasonal.add(s);
            deseasonalised.add(series.get(i) - s);
            // Remainder is observed minus trend minus seasonal. Where trend is missing
            // (the ends of the series) the remainder is undefined and left as NaN so a
            // downstream detector skips it rather than scoring against a made-up value.
            remainder.add(trend.get(i) == null ? Double.NaN : series.get(i) - trend.get(i) - s);
        }
        return new Decomposition(trend, seasonal, remainder, deseasonalised, period);
    }

    /**
     * Centred moving average. Endpoints, where a full symmetric window does not fit,
     * are returned as {@code null} rather than a partial-window estimate, because a
     * short-window average would be biased toward recent values and would masquerade
     * as a trend shift.
     */
    private static List<Double> movingAverage(List<Double> series, int period) {
        int n = series.size();
        List<Double> result = new ArrayList<>(n);
        int half = period / 2;
        for (int i = 0; i < n; i++) {
            if (i - half < 0 || i + half >= n) {
                result.add(null);
                continue;
            }
            double sum = 0.0;
            if (period % 2 == 0) {
                // Even window: average the two centred halves for symmetry.
                for (int k = i - half; k <= i + half - 1; k++) {
                    sum += series.get(k);
                }
                sum /= period;
            } else {
                for (int k = i - half; k <= i + half; k++) {
                    sum += series.get(k);
                }
                sum /= period;
            }
            result.add(sum);
        }
        return result;
    }
}
