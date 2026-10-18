package com.pesaguard.backend.analytics.algorithm.reconciliation;

/**
 * Core value types for reconciliation.
 *
 * <p>Reconciliation answers one question: which ledger entry and which provider
 * record describe the same real-world payment? Every match type here maps to a
 * distinct, auditable reason a pair was (or was not) joined. That reason is stored
 * with the match so an operator can later explain it, because the platform treats a
 * silent or unexplained match as worse than an unmatched pair left for review.
 *
 * <p>Nothing in this file decides money movement. It only classifies candidate
 * pairs; the effects of a confirmed match are applied by the reconciliation service
 * under a transaction and an audit trail.
 */
public final class ReconciliationTypes {

    private ReconciliationTypes() {
    }

    /** Which reconciliation rule produced a match. */
    public enum MatchType {
        /** Same provider receipt / transaction id. Unambiguous. */
        EXACT_RECEIPT,
        /** Agree on amount, party, account ref, within a time window. */
        COMPOSITE_KEY,
        /** Account references are similar but not identical (typo/transposition). */
        FUZZY_REFERENCE,
        /** One provider record equals the sum of several ledger entries. */
        SPLIT_PAYMENT,
        /** One ledger entry equals the sum of several provider records. */
        PARTIAL_PAYMENT,
        /** Matched only after the time window was relaxed for a late callback. */
        TIME_WINDOW_TOLERANCE,
        /** Chosen by optimal assignment among several equally-plausible pairs. */
        OPTIMAL_ASSIGNMENT
    }

    /** Confidence attached to a match, driving whether it auto-settles or queues. */
    public enum Confidence {
        EXACT,
        HIGH,
        MEDIUM,
        REVIEW
    }

    /** A candidate record from one side of the reconciliation. */
    public record Record(
            long id,
            double amount,
            long timestampMillis,
            String msisdn,
            String accountReference,
            String receiptId) {

        /** Canonical amount in minor units (cents) to avoid float drift in comparisons. */
        public long amountMinorUnits() {
            return Math.round(amount * 100.0);
        }
    }

    /** A confirmed match between a ledger record and a provider record. */
    public record Match(
            long ledgerId,
            long providerId,
            MatchType type,
            Confidence confidence,
            double score,
            String explanation) {
    }

    /** A pair that did not meet the bar to auto-match, retained for review. */
    public record Unmatched(long recordId, String reason) {
    }
}
