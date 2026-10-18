package com.pesaguard.backend.analytics.algorithm.anomaly;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Velocity checks and structuring detection.
 *
 * <p>Velocity asks "how much activity in a recent window?" rather than "is this one
 * value unusual?". A payer sending many small transfers in quick succession is normal
 * transaction-by-transaction but abnormal in aggregate; velocity is what catches that.
 * Events are bucketed into sliding windows and the count and sum within each are
 * compared against limits.
 *
 * <p>Structuring is the specific evasion of splitting an amount into pieces just under
 * a reporting threshold - sending four transfers of 9,900 instead of one of 39,600 to
 * stay under a 10,000 limit. Individually each transfer is innocuous; the pattern -
 * repeated near-threshold amounts in a short span - is the signal. Detecting it means
 * looking for a cluster of amounts that all fall in a band just below a known limit.
 *
 * <p>Both produce leads for review, never autonomous action. A high velocity count can
 * be a legitimate busy merchant; a cluster of near-threshold amounts can be ordinary
 * recurring payments. These narrow attention; they do not decide.
 */
public final class VelocityAndStructuringDetector {

    private VelocityAndStructuringDetector() {
    }

    /** A timestamped transaction to analyse. */
    public record Transaction(String id, String accountId, double amount, long timestampMillis) {
    }

    /** A velocity breach over one account within one window. */
    public record VelocityBreach(String accountId, long windowStart, long windowEnd,
                                 int count, double sum, String limitKind) {
    }

    /** A detected structuring pattern over one account. */
    public record StructuringPattern(String accountId, int nearThresholdCount,
                                     double totalAmount, double threshold, double bandLow) {
    }

    /**
     * Sliding-window velocity check per account.
     *
     * <p>For each account, every transaction opens a window ending at its own time and
     * spanning {@code windowMillis} backwards; the count and sum of transactions inside
     * that window are compared against {@code maxCount} and {@code maxSum}. A breach on
     * either limit is reported once per account per window position, which keeps the
     * output to one row per offending window rather than one per transaction.
     *
     * <p>Only transactions belonging to the same account are ever aggregated together,
     * so a busy neighbour cannot inflate another account's velocity.
     */
    public static List<VelocityBreach> velocityCheck(List<Transaction> transactions,
                                                     long windowMillis, int maxCount, double maxSum) {
        List<VelocityBreach> breaches = new ArrayList<>();
        // Group by account so windows never cross account boundaries.
        java.util.Map<String, List<Transaction>> byAccount = new java.util.HashMap<>();
        for (Transaction t : transactions) {
            byAccount.computeIfAbsent(t.accountId(), k -> new ArrayList<>()).add(t);
        }
        for (java.util.Map.Entry<String, List<Transaction>> entry : byAccount.entrySet()) {
            List<Transaction> accountTxns = entry.getValue();
            accountTxns.sort(Comparator.comparingLong(Transaction::timestampMillis));
            for (int i = 0; i < accountTxns.size(); i++) {
                long windowEnd = accountTxns.get(i).timestampMillis();
                long windowStart = windowEnd - windowMillis;
                int count = 0;
                double sum = 0.0;
                for (Transaction t : accountTxns) {
                    if (t.timestampMillis() >= windowStart && t.timestampMillis() <= windowEnd) {
                        count++;
                        sum += t.amount();
                    }
                }
                if (count > maxCount) {
                    breaches.add(new VelocityBreach(entry.getKey(), windowStart, windowEnd,
                            count, sum, "count"));
                } else if (sum > maxSum) {
                    breaches.add(new VelocityBreach(entry.getKey(), windowStart, windowEnd,
                            count, sum, "sum"));
                }
            }
        }
        return breaches;
    }

    /**
     * Structuring detection: a cluster of amounts just under a threshold.
     *
     * <p>Counts, per account, the transactions falling in {@code [threshold - band,
     * threshold)} within {@code windowMillis} of the most recent such transaction. When
     * that count reaches {@code minClusterSize}, it is reported as a structuring lead.
     *
     * <p>The band is measured as an absolute amount, not a percentage, because
     * reporting thresholds are absolute. An amount exactly at the threshold is excluded
     * - a legal transaction at the limit is not structuring; it is the ones deliberately
     * kept just below it that matter.
     *
     * @param threshold      the reporting/settlement limit being evaded
     * @param band           width of the just-below band, in currency units
     * @param windowMillis   time span over which the cluster must occur
     * @param minClusterSize how many near-threshold amounts constitute a pattern
     */
    public static List<StructuringPattern> detectStructuring(List<Transaction> transactions,
                                                             double threshold, double band,
                                                             long windowMillis, int minClusterSize) {
        List<StructuringPattern> patterns = new ArrayList<>();
        java.util.Map<String, List<Transaction>> byAccount = new java.util.HashMap<>();
        for (Transaction t : transactions) {
            // Only amounts strictly inside the band are relevant to structuring.
            if (t.amount() >= threshold - band && t.amount() < threshold) {
                byAccount.computeIfAbsent(t.accountId(), k -> new ArrayList<>()).add(t);
            }
        }
        for (java.util.Map.Entry<String, List<Transaction>> entry : byAccount.entrySet()) {
            List<Transaction> near = entry.getValue();
            near.sort(Comparator.comparingLong(Transaction::timestampMillis));
            // Slide a window over the near-threshold transactions and find the densest
            // cluster; report it once per account.
            int bestCount = 0;
            double bestSum = 0.0;
            for (int i = 0; i < near.size(); i++) {
                long windowStart = near.get(i).timestampMillis();
                long windowEnd = windowStart + windowMillis;
                int count = 0;
                double sum = 0.0;
                for (int j = i; j < near.size() && near.get(j).timestampMillis() <= windowEnd; j++) {
                    count++;
                    sum += near.get(j).amount();
                }
                if (count > bestCount) {
                    bestCount = count;
                    bestSum = sum;
                }
            }
            if (bestCount >= minClusterSize) {
                patterns.add(new StructuringPattern(entry.getKey(), bestCount, bestSum,
                        threshold, threshold - band));
            }
        }
        return patterns;
    }
}
