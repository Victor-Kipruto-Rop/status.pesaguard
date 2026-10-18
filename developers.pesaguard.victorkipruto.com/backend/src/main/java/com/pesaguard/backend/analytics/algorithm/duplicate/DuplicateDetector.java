package com.pesaguard.backend.analytics.algorithm.duplicate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Duplicate and near-duplicate transaction detection.
 *
 * <p>A true duplicate is the same economic event recorded twice - the same amount,
 * from the same payer, to the same payee, at effectively the same moment - usually
 * caused by a retried webhook or a double-submitted request. An exact duplicate is
 * cheap to catch; a near-duplicate (same everything, a few seconds apart, or a
 * one-digit difference in the reference) is the hard and important case, because a
 * naive de-dup either misses it or, worse, collapses two genuinely distinct
 * transactions into one and loses money.
 *
 * <p>Detection is a similarity comparison within a small candidate set rather than
 * an all-pairs scan: transactions are bucketed by subject and only compared within a
 * bucket inside a time window, which keeps the cost linear in practice. A pair is
 * reported with its component scores so a human reviewer can see *why* it was
 * flagged, not just that it was.
 *
 * <p>This class reports candidate duplicates for review. It never mutates, merges,
 * or drops a transaction - collapsing records is a financial operation that requires
 * explicit, audited action downstream.
 */
public final class DuplicateDetector {

    private DuplicateDetector() {
    }

    /** A transaction as seen by the detector. */
    public record Transaction(
            long id,
            double amount,
            long timestampMillis,
            String payer,
            String payee,
            String reference) {
    }

    /** Component similarity scores for a candidate pair, each in {@code [0, 1]}. */
    public record SimilarityScores(
            double amountScore,
            double timeScore,
            double partyScore,
            double referenceScore,
            double overall) {
    }

    /** A flagged pair with its scores, ready for human review. */
    public record DuplicatePair(long leftId, long rightId, SimilarityScores scores) {
    }

    /** Tunable thresholds; defaults are chosen for review, not auto-action. */
    public record Config(
            double amountTolerance,      // absolute amount difference tolerated
            long timeWindowMillis,       // max separation to be considered the same event
            double referenceThreshold,   // min string similarity to call references equal
            double overallThreshold) {   // min overall score to flag the pair
        public static Config defaults() {
            return new Config(0.01, 60_000L, 0.90, 0.85);
        }
    }

    /**
     * Find candidate duplicate pairs in a batch.
     *
     * <p>Complexity is kept near-linear by bucketing on the payer and comparing only
     * within a bucket and only inside the time window. Two transactions from
     * different payers are never compared, because they cannot be the same economic
     * event and comparing them would only manufacture false positives.
     */
    public static List<DuplicatePair> detect(List<Transaction> transactions, Config config) {
        if (transactions == null || transactions.size() < 2) {
            return List.of();
        }
        Map<String, List<Transaction>> byPayer = new LinkedHashMap<>();
        for (Transaction t : transactions) {
            byPayer.computeIfAbsent(t.payer(), p -> new ArrayList<>()).add(t);
        }
        List<DuplicatePair> pairs = new ArrayList<>();
        for (List<Transaction> bucket : byPayer.values()) {
            bucket.sort((a, b) -> Long.compare(a.timestampMillis(), b.timestampMillis()));
            for (int i = 0; i < bucket.size(); i++) {
                for (int j = i + 1; j < bucket.size(); j++) {
                    long gap = bucket.get(j).timestampMillis() - bucket.get(i).timestampMillis();
                    if (gap > config.timeWindowMillis()) {
                        // Sorted by time: once the gap exceeds the window, no later
                        // element can be within it either.
                        break;
                    }
                    SimilarityScores scores = score(bucket.get(i), bucket.get(j), config);
                    if (scores.overall() >= config.overallThreshold()) {
                        pairs.add(new DuplicatePair(
                                bucket.get(i).id(), bucket.get(j).id(), scores));
                    }
                }
            }
        }
        return pairs;
    }

    /**
     * Score a single candidate pair.
     *
     * <p>Each component is normalised to {@code [0, 1]} so they can be combined. The
     * overall score is the mean of the components, which deliberately gives no single
     * feature veto power - a pair that matches on everything but the reference still
     * scores high, matching the fact that reference typos are common and usually
     * harmless.
     */
    public static SimilarityScores score(Transaction a, Transaction b, Config config) {
        double amountScore = Math.abs(a.amount() - b.amount()) <= config.amountTolerance() ? 1.0 : 0.0;
        double separation = Math.abs(a.timestampMillis() - b.timestampMillis());
        double timeScore = 1.0 - Math.min(1.0, (double) separation / config.timeWindowMillis());
        double partyScore = a.payee().equals(b.payee()) ? 1.0 : 0.0;
        double referenceScore = com.pesaguard.backend.analytics.algorithm.similarity.StringSimilarity
                .jaroWinkler(a.reference(), b.reference());
        boolean qualifies = referenceScore >= config.referenceThreshold() || partyScore == 1.0;
        double overall = qualifies
                ? (amountScore + timeScore + partyScore + referenceScore) / 4.0
                : 0.0;
        return new SimilarityScores(amountScore, timeScore, partyScore, referenceScore, overall);
    }
}
