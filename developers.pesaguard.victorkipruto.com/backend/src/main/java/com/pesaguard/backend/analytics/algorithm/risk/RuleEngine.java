package com.pesaguard.backend.analytics.algorithm.risk;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Weighted rule engine for risk scoring.
 *
 * <p>A rule engine expresses risk as a set of named, individually-testable rules,
 * each contributing a weighted score when its predicate is true. The total risk is
 * the sum of the firing rules' weights. This is deliberately transparent: every score
 * can be decomposed into exactly which rules fired and how much each contributed, so
 * a decision can be explained to an operator or an auditor after the fact.
 *
 * <p>Weights are supplied by configuration, not hard-coded, so a risk appetite change
 * is a configuration edit rather than a code change. The engine itself has no
 * financial authority - it produces a score and the contributing rules; what to do
 * about that score (hold, review, release) is decided by the service layer.
 *
 * <p>Rules are evaluated in a deterministic order and the contributions are recorded
 * in that order, so identical inputs always produce an identical, reproducible score.
 */
public final class RuleEngine {

    private RuleEngine() {
    }

    /** A single weighted rule. */
    public interface Rule {
        /** Stable identifier used in the audit trail. */
        String name();

        /** Weight contributed when {@link #test} is true. Must be non-negative. */
        double weight();

        /** Predicate evaluated against the evaluation context. */
        boolean test(RiskContext context);
    }

    /**
     * Inputs a rule may consult. Kept as a simple record so a rule can be written and
     * tested without any dependency on the database or Spring.
     */
    public record RiskContext(
            double amount,
            int velocityCount,
            double velocitySum,
            double baselineMean,
            double baselineStdDev,
            double referenceSimilarity,
            boolean crossBorder,
            boolean newDevice,
            int failedAttempts,
            boolean onWatchlist,
            Map<String, Double> extra) {

        public RiskContext {
            extra = extra == null ? Map.of() : Map.copyOf(extra);
        }

        /** Read an optional custom signal by name, defaulting to zero when absent. */
        public double signal(String key) {
            return extra.getOrDefault(key, 0.0);
        }
    }

    /** The score plus the audit trail of which rules fired and by how much. */
    public record ScoreResult(double totalScore, List<Contribution> contributions, boolean anyRuleFired) {
    }

    /** One rule's contribution to the total, kept for the audit trail. */
    public record Contribution(String rule, double weight, boolean fired) {
    }

    /**
     * Evaluate all rules against the context and sum the weights of those that fire.
     *
     * <p>Every rule is recorded, fired or not, so an operator can see the rules that
     * were considered and did not fire - that negative evidence is often as important
     * as the positive when explaining why a transaction was released.
     */
    public static ScoreResult evaluate(List<Rule> rules, RiskContext context) {
        List<Contribution> contributions = new ArrayList<>();
        double total = 0.0;
        boolean anyFired = false;
        for (Rule rule : rules) {
            boolean fired = rule.test(context);
            if (fired) {
                total += rule.weight();
                anyFired = true;
            }
            contributions.add(new Contribution(rule.name(), rule.weight(), fired));
        }
        return new ScoreResult(total, contributions, anyFired);
    }

    /**
     * Build the default rule set from a weight map.
     *
     * <p>The weights come from configuration. The rules themselves are the stable,
     * reviewable policy; only their weights are environment-tunable. A missing weight
     * defaults to zero, which disables that rule rather than failing closed, because a
     * misconfiguration should be caught by the weights being reviewed, not silently
     * crash scoring at runtime.
     */
    public static List<Rule> defaultRules(Map<String, Double> weights) {
        Map<String, Double> w = new LinkedHashMap<>(weights);
        List<Rule> rules = new ArrayList<>();
        rules.add(new SimpleRule("large_amount", w.getOrDefault("large_amount", 0.0),
                c -> c.amount() > 50_000.0));
        rules.add(new SimpleRule("high_velocity", w.getOrDefault("high_velocity", 0.0),
                c -> c.velocityCount() > 5));
        rules.add(new SimpleRule("velocity_sum_high", w.getOrDefault("velocity_sum_high", 0.0),
                c -> c.velocitySum() > 100_000.0));
        rules.add(new SimpleRule("amount_deviation", w.getOrDefault("amount_deviation", 0.0),
                c -> c.baselineStdDev() > 0.0
                        && Math.abs(c.amount() - c.baselineMean()) > 3.0 * c.baselineStdDev()));
        rules.add(new SimpleRule("weak_reference_match", w.getOrDefault("weak_reference_match", 0.0),
                c -> c.referenceSimilarity() < 0.5));
        rules.add(new SimpleRule("cross_border", w.getOrDefault("cross_border", 0.0),
                RiskContext::crossBorder));
        rules.add(new SimpleRule("new_device", w.getOrDefault("new_device", 0.0),
                RiskContext::newDevice));
        rules.add(new SimpleRule("failed_attempts", w.getOrDefault("failed_attempts", 0.0),
                c -> c.failedAttempts() >= 3));
        rules.add(new SimpleRule("watchlist", w.getOrDefault("watchlist", 0.0),
                RiskContext::onWatchlist));
        return rules;
    }

    /** Minimal rule implementation: a name, a weight, and a predicate. */
    private record SimpleRule(String name, double weight, java.util.function.Predicate<RiskContext> predicate)
            implements Rule {
        @Override
        public boolean test(RiskContext context) {
            return predicate.test(context);
        }
    }
}
