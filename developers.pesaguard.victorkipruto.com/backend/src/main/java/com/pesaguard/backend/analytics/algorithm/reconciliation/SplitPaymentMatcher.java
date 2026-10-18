package com.pesaguard.backend.analytics.algorithm.reconciliation;

import java.util.ArrayList;
import java.util.List;

import static com.pesaguard.backend.analytics.algorithm.reconciliation.ReconciliationTypes.Confidence;
import static com.pesaguard.backend.analytics.algorithm.reconciliation.ReconciliationTypes.MatchType;
import static com.pesaguard.backend.analytics.algorithm.reconciliation.ReconciliationTypes.Record;

/**
 * Split and partial-payment matching.
 *
 * <p>A split is one provider payment that several ledger entries add up to; a
 * partial is the reverse, several provider payments that add up to one ledger entry.
 * Both are common and neither can be matched one-to-one, because no single pair has
 * equal amounts. The relationship lives in the *sum*, so matching has to be done on
 * sums.
 *
 * <p>Two strategies are provided. The greedy matcher walks candidates in amount
 * order and adds any that keep the running total inside tolerance; it is fast and
 * predictable but not optimal, so a valid grouping can be missed. The subset-sum
 * matcher searches for an exact combination that hits the target within tolerance;
 * it is exhaustive over the candidate set and finds a grouping the greedy pass would
 * overlook, at the cost of being pseudo-polynomial in the number of candidates. The
 * subset-sum path is the one trusted for a final decision; greedy is a fast pre-pass.
 *
 * <p>Only candidate records already filtered to the same party and a plausible time
 * window are passed in. A sum that ties together amounts from unrelated parties would
 * misattribute money, so that filtering is a precondition, not something this class
 * re-checks.
 */
public final class SplitPaymentMatcher {

    private SplitPaymentMatcher() {
    }

    /** A grouping of records that together match one target record. */
    public record SplitGroup(long targetId, List<Long> componentIds, double totalAmount,
                             double targetAmount, MatchType type, Confidence confidence) {
    }

    /**
     * Greedy split matcher: greedily accumulate components up to the target.
     *
     * <p>Records are tried largest-first within tolerance. Greedy is not guaranteed
     * to find a valid grouping even when one exists, so a null result means "not
     * found this way", never "no grouping exists".
     *
     * @param target     the single record to explain
     * @param components candidate records that might sum to the target
     * @param tolerance  absolute amount tolerance on the total
     */
    public static SplitGroup greedyMatch(Record target, List<Record> components, double tolerance) {
        if (components == null || components.isEmpty()) {
            return null;
        }
        List<Record> sorted = new ArrayList<>(components);
        // Descending amount: taking the largest fitting component first reaches the
        // target in the fewest steps and reduces the chance of a dead-end total.
        sorted.sort((a, b) -> Double.compare(b.amount(), a.amount()));
        List<Long> chosen = new ArrayList<>();
        double running = 0.0;
        for (Record component : sorted) {
            if (Math.abs(running + component.amount() - target.amount()) <= tolerance) {
                chosen.add(component.id());
                running += component.amount();
                break;
            }
            if (running + component.amount() <= target.amount() + tolerance) {
                chosen.add(component.id());
                running += component.amount();
            }
        }
        if (chosen.isEmpty() || Math.abs(running - target.amount()) > tolerance) {
            return null;
        }
        return new SplitGroup(target.id(), chosen, running, target.amount(),
                MatchType.SPLIT_PAYMENT, Confidence.MEDIUM);
    }

    /**
     * Subset-sum split matcher: search for a combination summing to the target.
     *
     * <p>Works in integer minor units so the search is over exact integers and never
     * drifts on floating-point rounding. The dynamic-programming table is capped by
     * {@code capMinor} (the target plus tolerance) so the memory stays bounded even
     * when individual components are large; candidates larger than the cap are
     * skipped because they cannot be part of a valid sum.
     *
     * <p>This is the decision-grade matcher. When it returns a group, that group is
     * proven to sum to the target within tolerance, which is why it - not the greedy
     * pass - is what an audit trail should cite.
     */
    public static SplitGroup subsetSumMatch(Record target, List<Record> components, double tolerance) {
        if (components == null || components.isEmpty()) {
            return null;
        }
        long targetMinor = target.amountMinorUnits();
        long toleranceMinor = Math.round(tolerance * 100.0);
        long capMinor = targetMinor + toleranceMinor;

        List<Record> usable = new ArrayList<>();
        for (Record component : components) {
            if (component.amountMinorUnits() <= capMinor) {
                usable.add(component);
            }
        }
        if (usable.isEmpty()) {
            return null;
        }

        int n = usable.size();
        int cap = (int) Math.min(capMinor, Integer.MAX_VALUE);
        // reachable[s] tracks whether subtotal s can be formed; viaComponent/prevSum
        // reconstruct the chosen set once a valid total is found.
        boolean[] reachable = new boolean[cap + 1];
        int[] viaComponent = new int[cap + 1];
        int[] prevSum = new int[cap + 1];
        java.util.Arrays.fill(viaComponent, -1);
        java.util.Arrays.fill(prevSum, -1);
        reachable[0] = true;

        for (int i = 0; i < n; i++) {
            int value = (int) usable.get(i).amountMinorUnits();
            // Iterate downward so each component is used at most once (0/1 knapsack).
            for (int s = cap; s >= value; s--) {
                if (reachable[s - value] && !reachable[s]) {
                    reachable[s] = true;
                    viaComponent[s] = i;
                    prevSum[s] = s - value;
                }
            }
        }

        // Prefer an exact hit; otherwise accept any total within tolerance of target.
        int bestSum = -1;
        for (int s = (int) targetMinor; s <= cap; s++) {
            if (reachable[s]) {
                bestSum = s;
                break;
            }
        }
        if (bestSum < 0) {
            return null;
        }

        // Reconstruct the chosen components by walking the predecessor chain.
        List<Long> chosen = new ArrayList<>();
        int cursor = bestSum;
        while (cursor > 0 && viaComponent[cursor] >= 0) {
            int componentIndex = viaComponent[cursor];
            chosen.add(usable.get(componentIndex).id());
            cursor = prevSum[cursor];
        }
        double total = bestSum / 100.0;
        return new SplitGroup(target.id(), chosen, total, target.amount(),
                MatchType.SPLIT_PAYMENT, Confidence.HIGH);
    }

    /**
     * Partial-payment matcher: the mirror of a split. One ledger entry is matched by
     * several provider records. Implemented as a subset-sum over the provider side,
     * because the arithmetic is identical - a group whose total equals the target.
     */
    public static SplitGroup partialPaymentMatch(Record ledgerTarget, List<Record> providerRecords,
                                                 double tolerance) {
        SplitGroup group = subsetSumMatch(ledgerTarget, providerRecords, tolerance);
        if (group == null) {
            return null;
        }
        return new SplitGroup(group.targetId(), group.componentIds(), group.totalAmount(),
                group.targetAmount(), MatchType.PARTIAL_PAYMENT, group.confidence());
    }

    /** Convenience: the set of component ids already consumed, to exclude from later passes. */
    public static List<Long> consumedIds(List<SplitGroup> groups) {
        List<Long> consumed = new ArrayList<>();
        for (SplitGroup group : groups) {
            consumed.addAll(group.componentIds());
        }
        return consumed;
    }
}
