package com.pesaguard.backend.analytics.algorithm.risk;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reputation scoring for MSISDNs and merchants.
 *
 * <p>Reputation is a slow-moving, longitudinal score that summarises a subject's
 * behaviour over a long history, in contrast to the per-transaction risk score which
 * is a fast, single-event judgement. A merchant with years of clean settlement earns
 * trust that makes an otherwise-odd transaction more tolerable; a payer repeatedly
 * involved in disputes accrues distrust that lowers the bar for review. Reputation
 * carries that memory.
 *
 * <p>The score is an exponentially-weighted average of past outcomes, so recent
 * behaviour counts more than ancient history but nothing is ever fully forgotten.
 * Outcomes are supplied by the caller as signed signals (a confirmed fraud is a
 * negative signal, a clean settlement a positive one); the scorer does not itself
 * decide what is good or bad, it only aggregates what it is told.
 *
 * <p>Scores are per-subject and never blended across subjects, so one bad actor does
 * not taint an unrelated merchant - the same tenant-isolation discipline applied
 * everywhere else holds inside the scorer.
 */
public final class ReputationScorer {

    private ReputationScorer() {
    }

    /** One observed outcome for a subject, contributed to the running reputation. */
    public record Outcome(String subject, double signal, long timestampMillis) {
    }

    /** A subject's current reputation in {@code [0, 1]}, where 1 is fully trusted. */
    public record Reputation(String subject, double score, int observationCount) {
    }

    /**
     * Compute an exponentially-weighted reputation per subject.
     *
     * <p>Outcomes are processed in ascending time order per subject (the scorer
     * sorts internally, so the caller need not). Each signal updates the running
     * score toward the new value with weight {@code alpha}: a larger alpha reacts
     * faster to recent events, a smaller one gives more weight to the long history.
     *
     * <p>Signals are clamped to {@code [-1, 1]} and the score is bounded to
     * {@code [0, 1]} by mapping the clamped weighted average from {@code [-1, 1]}
     * linearly. A subject with no observations starts neutral at 0.5, so an unknown
     * payer is neither trusted nor distrusted - an unknown subject must not inherit a
     * favourable score it has not earned.
     *
     * @param outcomes all observed outcomes across subjects
     * @param alpha    smoothing factor in {@code (0, 1]}
     */
    public static Map<String, Reputation> score(List<Outcome> outcomes, double alpha) {
        if (outcomes == null || outcomes.isEmpty()) {
            return Map.of();
        }
        if (alpha <= 0.0 || alpha > 1.0) {
            throw new IllegalArgumentException("alpha must be in (0, 1]");
        }
        // Group by subject, then order each group's outcomes by time so the EWMA
        // update is applied chronologically and is therefore deterministic.
        Map<String, List<Outcome>> bySubject = new LinkedHashMap<>();
        for (Outcome outcome : outcomes) {
            bySubject.computeIfAbsent(outcome.subject(), s -> new java.util.ArrayList<>()).add(outcome);
        }
        Map<String, Reputation> reputations = new HashMap<>();
        for (Map.Entry<String, List<Outcome>> entry : bySubject.entrySet()) {
            entry.getValue().sort((a, b) -> Long.compare(a.timestampMillis(), b.timestampMillis()));
            double score = 0.5; // neutral start for an unknown subject
            for (Outcome outcome : entry.getValue()) {
                double clamped = Math.max(-1.0, Math.min(1.0, outcome.signal()));
                score = alpha * (clamped + 1.0) / 2.0 + (1.0 - alpha) * score;
            }
            reputations.put(entry.getKey(),
                    new Reputation(entry.getKey(), score, entry.getValue().size()));
        }
        return reputations;
    }

    /**
     * Look up a single subject's reputation, defaulting to neutral when unknown.
     *
     * <p>The neutral default is deliberate: an absent reputation must not be read as
     * a good reputation. Callers that treat a missing entry as "trusted" would create
     * a bypass where a brand-new, never-seen subject sails through.
     */
    public static double reputationOf(Map<String, Reputation> reputations, String subject) {
        Reputation reputation = reputations.get(subject);
        return reputation == null ? 0.5 : reputation.score();
    }
}
