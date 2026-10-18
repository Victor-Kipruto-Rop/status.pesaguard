package com.pesaguard.backend.analytics.algorithm.anomaly;

import java.util.ArrayList;
import java.util.List;

import static com.pesaguard.backend.analytics.algorithm.similarity.StringSimilarity.jaroWinkler;
import static com.pesaguard.backend.analytics.algorithm.similarity.StringSimilarity.levenshtein;

/**
 * Duplicate and near-duplicate transaction detection.
 *
 * <p>A true duplicate is the same transaction submitted twice, usually a provider
 * retry or a double-submitted batch. A near-duplicate is a second transaction that is
 * suspiciously similar but not identical - the same amount and payer, moments apart,
 * with a reference differing by a character or two. Both must be caught because both
 * can represent a real double-charge, but they are distinguished because a true
 * duplicate is almost certainly an error while a near-duplicate may be a legitimate
 * repeat.
 *
 * <p>Two records are compared on a composite: exact amount (in minor units, so cent-
 * level rounding never hides a duplicate), closeness in time, and similarity of the
 * reference string. Records are considered only within a time window, which both keeps
 * the comparison count down and reflects the reality that a duplicate arrives shortly
 * after the original, not days later.
 *
 * <p>This flags candidates for de-duplication; it does not delete or reverse anything.
 * Removing a financial record is irreversible and requires the authority the pipeline
 * does not have, so the detector surfaces pairs and the decision is made by a human or
 * a higher-level policy.
 */
public final class DuplicateDetector {

    private DuplicateDetector() {
    }

    /** A transaction to be compared for duplication. */
    public record Candidate(String id, String accountId, String reference,
                            long amountMinorUnits, long timestampMillis) {
    }

    /** A detected pair of duplicate or near-duplicate transactions. */
    public record DuplicatePair(String firstId, String secondId, double referenceSimilarity,
                                boolean exactAmount, boolean exactReference, long timeDeltaMillis,
                                DuplicateKind kind) {
    }

    /** How close the pair is. */
    public enum DuplicateKind {
        EXACT,
        NEAR
    }

    /**
     * Find duplicate and near-duplicate pairs among candidates.
     *
     * <p>Candidates are grouped by account first so unrelated payers are never
     * compared. Within each account, every pair whose timestamps fall inside
     * {@code timeWindowMillis} is scored. A pair is a duplicate when the amounts match
     * exactly; it is a near-duplicate when the amounts match and the references are
     * within {@code referenceThreshold} similarity. Amount equality is required in both
     * cases because two different amounts are not a duplication of one event regardless
     * of how similar the references look.
     *
     * @param referenceThreshold minimum reference similarity for a near-duplicate
     */
    public static List<DuplicatePair> detect(List<Candidate> candidates, long timeWindowMillis,
                                             double referenceThreshold) {
        List<DuplicatePair> pairs = new ArrayList<>();
        java.util.Map<String, List<Candidate>> byAccount = new java.util.HashMap<>();
        for (Candidate c : candidates) {
            byAccount.computeIfAbsent(c.accountId(), k -> new ArrayList<>()).add(c);
        }
        for (List<Candidate> group : byAccount.values()) {
            for (int i = 0; i < group.size(); i++) {
                for (int j = i + 1; j < group.size(); j++) {
                    Candidate a = group.get(i);
                    Candidate b = group.get(j);
                    long delta = Math.abs(a.timestampMillis() - b.timestampMillis());
                    if (delta > timeWindowMillis) {
                        continue;
                    }
                    // Amount must match exactly (in minor units) to be a duplicate at all.
                    if (a.amountMinorUnits() != b.amountMinorUnits()) {
                        continue;
                    }
                    double similarity = combinedReferenceSimilarity(a.reference(), b.reference());
                    boolean exactReference = a.reference().equals(b.reference());
                    DuplicateKind kind = exactReference ? DuplicateKind.EXACT : DuplicateKind.NEAR;
                    if (exactReference || similarity >= referenceThreshold) {
                        pairs.add(new DuplicatePair(a.id(), b.id(), similarity, true,
                                exactReference, delta, kind));
                    }
                }
            }
        }
        return pairs;
    }

    /**
     * Combined reference similarity: the higher of Levenshtein and Jaro-Winkler.
     *
     * <p>The two metrics catch different typos - Levenshtein a swapped or inserted
     * digit, Jaro-Winkler a shared prefix with a changed tail. Taking the maximum means a
     * near-duplicate is flagged if either metric is satisfied, at the cost of a slightly
     * higher false-positive rate. For a pre-check that only routes candidates to review,
     * favouring recall (catching real duplicates) over precision is the right trade.
     */
    private static double combinedReferenceSimilarity(String a, String b) {
        if (a == null || b == null) {
            return 0.0;
        }
        return Math.max(levenshtein(a, b), jaroWinkler(a, b));
    }
}
