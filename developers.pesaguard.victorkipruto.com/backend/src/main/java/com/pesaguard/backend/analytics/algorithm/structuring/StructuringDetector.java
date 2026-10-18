package com.pesaguard.backend.analytics.algorithm.structuring;

import java.util.ArrayList;
import java.util.List;

/**
 * Structuring ("smurfing") detection.
 *
 * <p>Structuring is the deliberate splitting of a large amount into several smaller
 * transactions to stay under a reporting or review threshold. A single 95,000 KES
 * transfer might trigger review; five transfers of 19,000 might not, even though
 * the economic substance is identical and the intent - to evade the threshold - is
 * the same.
 *
 * <p>Detection rests on the "just under" pattern: a cluster of transactions that
 * each land in a narrow band below a known limit is far less likely to be
 * coincidence than a single one, because the joint probability of several
 * independent amounts all falling just below the threshold is tiny. The band width
 * and the minimum cluster size are the two parameters that trade sensitivity
 * against false positives; both must be tuned against the real reporting threshold,
 * not guessed.
 *
 * <p>A detection here is a suspicion, never a conclusion. The signal is surfaced for
 * review; it is not itself proof of structuring.
 */
public final class StructuringDetector {

    private StructuringDetector() {
    }

    /** One transaction considered by the detector. */
    public record Candidate(long id, double amount, long timestampMillis, String subject) {
    }

    /** A detected cluster of sub-threshold transactions. */
    public record Detection(
            double reportingLimit,
            int transactionCount,
            double totalAmount,
            double averageAmount,
            double closestToLimit,
            long windowStartMillis,
            long windowEndMillis,
            List<Long> transactionIds) {
    }

    /**
     * Detect structuring against a single reporting limit.
     *
     * @param candidates     transactions to scan (any order)
     * @param reportingLimit the threshold an attacker is trying to stay under
     * @param bandFraction   fraction of the limit defining the "just under" band;
     *                       e.g. 0.10 means amounts in {@code [0.90*limit, limit)}
     * @param minCount       minimum number of transactions to call it a pattern
     * @param windowMillis   transactions further apart than this are treated as
     *                       separate episodes
     */
    public static List<Detection> detect(List<Candidate> candidates, double reportingLimit,
                                         double bandFraction, int minCount, long windowMillis) {
        if (candidates == null || candidates.isEmpty() || reportingLimit <= 0.0) {
            return List.of();
        }
        double lowerBound = reportingLimit * (1.0 - bandFraction);
        List<Candidate> ordered = new ArrayList<>(candidates);
        ordered.sort((a, b) -> Long.compare(a.timestampMillis(), b.timestampMillis()));

        List<Detection> detections = new ArrayList<>();
        List<Candidate> cluster = new ArrayList<>();
        long windowStart = Long.MAX_VALUE;

        for (Candidate candidate : ordered) {
            boolean inBand = candidate.amount() >= lowerBound && candidate.amount() < reportingLimit;
            boolean withinWindow = candidate.timestampMillis() - windowStart <= windowMillis;
            if (inBand && (cluster.isEmpty() || withinWindow)) {
                if (cluster.isEmpty()) {
                    windowStart = candidate.timestampMillis();
                }
                cluster.add(candidate);
            } else {
                // Window broke or point left the band: close out the cluster.
                if (cluster.size() >= minCount) {
                    detections.add(buildDetection(cluster, reportingLimit));
                }
                cluster = new ArrayList<>();
                if (inBand) {
                    windowStart = candidate.timestampMillis();
                    cluster.add(candidate);
                }
            }
        }
        if (cluster.size() >= minCount) {
            detections.add(buildDetection(cluster, reportingLimit));
        }
        return detections;
    }

    private static Detection buildDetection(List<Candidate> cluster, double reportingLimit) {
        double total = cluster.stream().mapToDouble(Candidate::amount).sum();
        double closest = cluster.stream()
                .mapToDouble(Candidate::amount)
                .max()
                .orElse(0.0);
        List<Long> ids = cluster.stream().map(Candidate::id).toList();
        return new Detection(
                reportingLimit,
                cluster.size(),
                total,
                total / cluster.size(),
                closest,
                cluster.get(0).timestampMillis(),
                cluster.get(cluster.size() - 1).timestampMillis(),
                ids);
    }

    /**
     * Scan several reporting thresholds at once. Real compliance regimes have more
     * than one limit (currency-reporting, per-transaction, daily aggregate), and an
     * attacker will target whichever they believe is weakest, so all must be watched
     * simultaneously rather than one at a time.
     */
    public static List<Detection> detectMultiLimit(List<Candidate> candidates,
                                                   List<Double> reportingLimits,
                                                   double bandFraction, int minCount,
                                                   long windowMillis) {
        List<Detection> all = new ArrayList<>();
        for (double limit : reportingLimits) {
            all.addAll(detect(candidates, limit, bandFraction, minCount, windowMillis));
        }
        return all;
    }
}
