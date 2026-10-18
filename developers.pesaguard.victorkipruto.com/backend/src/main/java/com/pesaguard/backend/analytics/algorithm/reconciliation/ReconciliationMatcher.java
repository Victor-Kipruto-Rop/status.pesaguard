package com.pesaguard.backend.analytics.algorithm.reconciliation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.pesaguard.backend.analytics.algorithm.similarity.StringSimilarity;

import static com.pesaguard.backend.analytics.algorithm.reconciliation.ReconciliationTypes.Confidence;
import static com.pesaguard.backend.analytics.algorithm.reconciliation.ReconciliationTypes.Match;
import static com.pesaguard.backend.analytics.algorithm.reconciliation.ReconciliationTypes.MatchType;
import static com.pesaguard.backend.analytics.algorithm.reconciliation.ReconciliationTypes.Record;

/**
 * Deterministic record-to-record reconciliation matcher.
 *
 * <p>This handles the unambiguous-to-high-confidence single-pair cases: exact
 * receipt match, composite-key match, and fuzzy reference match. The harder
 * one-to-many and many-to-one cases (splits, partials) and the optimal assignment
 * of ambiguous groups are delegated to dedicated components; keeping them separate
 * means each can be tested against its own failure modes without the whole matcher
 * changing.
 *
 * <p>Matching is deterministic: the same two ledgers always produce the same
 * matches in the same order. Given equal candidate scores, the pair with the lower
 * ledger id wins, so there is no dependence on iteration order or on a random tie
 * break. A reconciliation whose output can change between two identical runs is not
 * reconcilable and cannot be audited.
 */
public final class ReconciliationMatcher {

    private ReconciliationMatcher() {
    }

    /** Tunables for the matcher, kept together so a rule change is a single edit. */
    public record Config(
            long timeWindowMillis,       // composite-key time tolerance
            long lateWindowMillis,       // extended window for delayed callbacks
            double fuzzyThreshold,       // min similarity to accept a fuzzy reference
            double amountTolerance) {    // absolute amount difference tolerated
        public static Config defaults() {
            return new Config(300_000L, 3_600_000L, 0.85, 0.01);
        }
    }

    /**
     * Match ledger records against provider records.
     *
     * <p>Each provider record is consumed at most once: once matched, its id is
     * recorded and it cannot be matched again, which prevents one provider payment
     * from satisfying two ledger entries. The converse (one ledger entry matched by
     * two provider records) is the split case and is handled elsewhere, not here.
     */
    public static List<Match> match(List<Record> ledger, List<Record> provider, Config config) {
        List<Match> matches = new ArrayList<>();
        Set<Long> consumedProvider = new HashSet<>();
        Set<Long> consumedLedger = new HashSet<>();

        // Index provider records by receipt id for the O(1) exact-match path.
        java.util.Map<String, List<Record>> byReceipt = new java.util.HashMap<>();
        for (Record p : provider) {
            if (p.receiptId() != null && !p.receiptId().isBlank()) {
                byReceipt.computeIfAbsent(p.receiptId(), r -> new ArrayList<>()).add(p);
            }
        }

        // 1. Exact receipt match: the strongest and cheapest signal. Done first so a
        //    receipt id always wins over a looser key that might also coincide.
        for (Record l : ledger) {
            if (l.receiptId() == null || l.receiptId().isBlank()) {
                continue;
            }
            List<Record> candidates = byReceipt.getOrDefault(l.receiptId(), List.of());
            for (Record p : candidates) {
                if (consumedProvider.contains(p.id())) {
                    continue;
                }
                matches.add(new Match(l.id(), p.id(), MatchType.EXACT_RECEIPT,
                        Confidence.EXACT, 1.0, "receipt id " + l.receiptId()));
                consumedProvider.add(p.id());
                consumedLedger.add(l.id());
                break;
            }
        }

        // 2 & 3. Composite and fuzzy match on everything not already exact-matched.
        for (Record l : ledger) {
            if (consumedLedger.contains(l.id())) {
                continue;
            }
            Match best = null;
            for (Record p : provider) {
                if (consumedProvider.contains(p.id())) {
                    continue;
                }
                Match candidate = comparePair(l, p, config);
                if (candidate != null && isBetter(candidate, best)) {
                    best = candidate;
                }
            }
            if (best != null) {
                matches.add(best);
                consumedProvider.add(best.providerId());
                consumedLedger.add(best.ledgerId());
            }
        }
        return matches;
    }

    /**
     * Score one ledger/provider pair against the composite and fuzzy rules.
     *
     * <p>Returns null when the pair cannot match. Amount agreement and MSISDN
     * agreement are hard gates - without them the pair is rejected outright - while
     * the account reference is a graded similarity. This ordering matters: two
     * records that agree on a typo'd reference but disagree on the amount are not a
     * match, and letting the reference pull them together would misattribute money.
     */
    private static Match comparePair(Record l, Record p, Config config) {
        if (!amountsAgree(l, p, config.amountTolerance())) {
            return null;
        }
        if (!sameParty(l, p)) {
            return null;
        }
        double similarity = StringSimilarity.jaroWinkler(
                safe(l.accountReference()), safe(p.accountReference()));
        long gap = Math.abs(l.timestampMillis() - p.timestampMillis());
        boolean exactReference = similarity >= 0.999;
        if (exactReference && gap <= config.timeWindowMillis()) {
            return new Match(l.id(), p.id(), MatchType.COMPOSITE_KEY, Confidence.HIGH,
                    similarity, "composite key within " + config.timeWindowMillis() + "ms");
        }
        if (similarity >= config.fuzzyThreshold() && gap <= config.timeWindowMillis()) {
            return new Match(l.id(), p.id(), MatchType.FUZZY_REFERENCE, Confidence.MEDIUM,
                    similarity, "reference similarity " + round(similarity));
        }
        // Late callback: the provider record arrived after the ledger window closed.
        if (gap <= config.lateWindowMillis()) {
            return new Match(l.id(), p.id(), MatchType.TIME_WINDOW_TOLERANCE, Confidence.REVIEW,
                    similarity, "late callback, gap " + gap + "ms");
        }
        return null;
    }

    /** Amount comparison in minor units to avoid floating-point equality surprises. */
    private static boolean amountsAgree(Record l, Record p, double tolerance) {
        long diff = Math.abs(l.amountMinorUnits() - p.amountMinorUnits());
        long toleranceMinor = Math.round(tolerance * 100.0);
        return diff <= toleranceMinor;
    }

    private static boolean sameParty(Record l, Record p) {
        return safe(l.msisdn()).equals(safe(p.msisdn()));
    }

    /** Deterministic preference: higher confidence, then higher score, then lower id. */
    private static boolean isBetter(Match candidate, Match current) {
        if (current == null) {
            return true;
        }
        int byConfidence = confidenceRank(candidate.confidence())
                - confidenceRank(current.confidence());
        if (byConfidence != 0) {
            return byConfidence > 0;
        }
        if (Double.compare(candidate.score(), current.score()) != 0) {
            return candidate.score() > current.score();
        }
        return candidate.ledgerId() < current.ledgerId();
    }

    private static int confidenceRank(Confidence confidence) {
        return switch (confidence) {
            case EXACT -> 4;
            case HIGH -> 3;
            case MEDIUM -> 2;
            case REVIEW -> 1;
        };
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
