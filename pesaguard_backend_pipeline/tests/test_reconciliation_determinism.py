"""Determinism and multiple-candidate regression tests for the Reconciliation
Engine (services 43, 45, 47).

Rule #26 requires reconciliation decisions to be deterministic and explainable;
Rule #27 requires explicit coverage of the "multiple candidates" case. Before the
tie-break fix, two internal records that tied on match score were resolved by
``max(...)``, which returns the FIRST max in input order -- so reordering the same
records changed which transaction a callback reconciled against. These tests pin
the deterministic behaviour.

Behaviour verified against the current engine before being written here.
"""

from __future__ import annotations

import pytest

from reconciliation_engine import ReconciliationEngine


ENGINE = ReconciliationEngine(tolerance_percent="0.5", window_seconds=900)


def _transaction(transaction_id, amount="100.00", timestamp="20260913120000", **extra):
    value = {
        "TransID": transaction_id,
        "TransAmount": amount,
        "MSISDN": "254700000000",
        "TransTime": timestamp,
        "BillRefNumber": f"order-{transaction_id}",
    }
    value.update(extra)
    return value


def _record(reference, amount="100.00", timestamp="2026-09-13T12:00:00Z", **extra):
    value = {
        "internal_ref": reference,
        "amount": amount,
        "phone_number": "254700000000",
        "timestamp": timestamp,
    }
    value.update(extra)
    return value


def _matched_ref(result):
    matched = result["evidence"]["matched_record"]
    return matched.get("internal_ref") if matched else None


# --- Multiple tied candidates: deterministic regardless of input order ----------


def test_tied_candidates_resolve_deterministically_regardless_of_order():
    # Three records tie on score; the same one must be chosen no matter the order.
    records = [_record("cand-C"), _record("cand-A"), _record("cand-B")]
    forward = _matched_ref(ENGINE.reconcile(_transaction("multi"), records))
    reversed_ = _matched_ref(ENGINE.reconcile(_transaction("multi"), list(reversed(records))))
    assert forward == reversed_


def test_tied_candidates_break_tie_by_internal_reference():
    # With equal scores, the lowest internal_ref (string order) wins deterministically.
    records = [_record("zzz"), _record("aaa"), _record("mmm")]
    chosen = _matched_ref(ENGINE.reconcile(_transaction("tiebreak"), records))
    assert chosen == "aaa"


def test_distinct_scores_still_pick_the_better_candidate():
    # A clearly stronger candidate must win over a weaker one, in either order.
    strong = _record("strong", amount="100.00")  # exact amount -> higher score
    weak = _record("weak", amount="100.40")       # within tolerance -> lower score
    assert _matched_ref(ENGINE.reconcile(_transaction("distinct"), [weak, strong])) == "strong"
    assert _matched_ref(ENGINE.reconcile(_transaction("distinct"), [strong, weak])) == "strong"


# --- Status outcomes remain stable for the key Rule #27 cases -------------------


def test_exact_match_is_matched():
    result = ENGINE.reconcile(_transaction("exact"), [_record("order-exact")])
    assert result["status"] == "MATCHED"
    assert _matched_ref(result) == "order-exact"


def test_missing_internal_record_is_unmatched():
    result = ENGINE.reconcile(_transaction("lonely"), [])
    assert result["status"] == "UNMATCHED"
    assert "missing_internal_record" in result["evidence"]["matching_rules"]


def test_duplicate_internal_reference_is_flagged():
    # Two internal records that both match the transaction's reference is a
    # duplicate, not a silent pick. The transaction reference must align with the
    # record references for the duplicate-reference branch to trigger.
    result = ENGINE.reconcile(
        _transaction("dup", BillRefNumber="same-ref"),
        [_record("same-ref"), _record("same-ref")],
    )
    assert result["status"] == "DUPLICATE"
    assert "duplicate_internal_reference" in result["evidence"]["matching_rules"]


def test_amount_beyond_tolerance_is_mismatch_not_silent_match():
    result = ENGINE.reconcile(
        _transaction("mismatch", amount="100.00"),
        [_record("order-mismatch", amount="150.00")],
    )
    assert result["status"] == "MISMATCH"
    assert "amount_mismatch" in result["evidence"]["matching_rules"]


def test_refund_requires_original_reference():
    result = ENGINE.reconcile(
        _transaction("refund-no-orig", TransactionType="Refund"),
        [_record("order-refund-no-orig")],
    )
    assert result["status"] == "EXCEPTION"
    assert "refund_requires_original_reference" in result["evidence"]["matching_rules"]


def test_invalid_amount_is_exception_not_silent():
    result = ENGINE.reconcile({"TransID": "bad", "TransAmount": "not-money"}, [])
    assert result["status"] == "EXCEPTION"


@pytest.mark.parametrize(
    "records",
    [
        [_record("b"), _record("a")],
        [_record("a"), _record("b")],
    ],
)
def test_tie_break_is_stable_across_permutations(records):
    # Both permutations of the same two tied records resolve to the same winner.
    assert _matched_ref(ENGINE.reconcile(_transaction("perm"), records)) == "a"
