"""Equal-score ambiguity and deterministic reconciliation tests (services 43, 45, 47).

Rule #26 requires reconciliation decisions to be deterministic and explainable;
Rule #27 requires explicit coverage of the "multiple candidates" case. Equal
top-scoring candidates are now returned as an ambiguity requiring review, rather
than silently choosing a record based on input order.
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


def test_tied_candidates_are_ambiguous_regardless_of_order():
    records = [_record("cand-C"), _record("cand-A"), _record("cand-B")]
    forward = ENGINE.reconcile(_transaction("multi"), records)
    reversed_ = ENGINE.reconcile(_transaction("multi"), list(reversed(records)))

    assert forward["status"] == reversed_["status"] == "EXCEPTION"
    assert forward["evidence"]["ambiguous_candidate_refs"] == ["CAND-A", "CAND-B", "CAND-C"]
    assert reversed_["evidence"]["ambiguous_candidate_refs"] == ["CAND-A", "CAND-B", "CAND-C"]
    assert _matched_ref(forward) is _matched_ref(reversed_) is None


def test_equal_scores_never_select_the_lowest_internal_reference():
    records = [_record("zzz"), _record("aaa"), _record("mmm")]
    result = ENGINE.reconcile(_transaction("tiebreak"), records)
    assert result["status"] == "EXCEPTION"
    assert result["evidence"]["matching_rules"] == ["ambiguous_top_scoring_candidates"]
    assert result["evidence"]["ambiguous_candidate_refs"] == ["AAA", "MMM", "ZZZ"]


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
def test_equal_score_permutations_are_review_outcomes(records):
    result = ENGINE.reconcile(_transaction("perm"), records)
    assert result["status"] == "EXCEPTION"
    assert result["evidence"]["ambiguous_candidate_refs"] == ["A", "B"]


# --- Exhaustive determinism across every reconciliation branch ---------------
#
# Rule #26 requires deterministic, explainable decisions; Rule #27 requires the
# "multiple candidates" case be covered. These tests permute the record list
# exhaustively and assert the engine always produces the same matched record and
# status -- covering the exact-match, same-reference, tolerant, amount-only,
# refund and duplicate-reference branches, not just the scored tie-break.


import itertools


def _all_permutations_agree(transaction, pool):
    outcomes = set()
    for perm in itertools.permutations(pool):
        result = ENGINE.reconcile(transaction, list(perm))
        outcomes.add((_matched_ref(result), result["status"]))
    assert len(outcomes) == 1, f"non-deterministic outcomes: {outcomes}"
    return outcomes.pop()


def test_all_permutations_of_tied_records_agree():
    ref, status = _all_permutations_agree(
        _transaction("perm-tied"),
        [_record("c"), _record("a"), _record("b")],
    )
    assert ref is None
    assert status == "EXCEPTION"


def test_all_permutations_of_distinct_scores_agree():
    ref, _ = _all_permutations_agree(
        _transaction("perm-distinct"),
        [_record("weak", amount="100.40"), _record("strong", amount="100.00")],
    )
    assert ref == "strong"


def test_all_permutations_of_amount_only_agree():
    ref, _ = _all_permutations_agree(
        _transaction("perm-amount"),
        [_record("a1", amount="100.10"), _record("a2", amount="100.05")],
    )
    assert ref is None


def test_duplicate_reference_is_deterministic_under_reordering():
    # Two reference-identical records must always resolve to DUPLICATE,
    # never a silent order-dependent pick.
    outcomes = set()
    for perm in itertools.permutations([_record("same"), _record("same")]):
        result = ENGINE.reconcile(_transaction("perm-dup", BillRefNumber="same"), list(perm))
        outcomes.add((_matched_ref(result), result["status"]))
    assert outcomes == {(None, "DUPLICATE")}


def test_exact_match_is_deterministic_under_reordering():
    # A single exact match among tolerated peers always resolves to it.
    ref, _ = _all_permutations_agree(
        _transaction("perm-exact", BillRefNumber="order-perm-exact"),
        [
            _record("order-perm-exact", amount="100.00"),
            _record("other", amount="100.20"),
        ],
    )
    assert ref == "order-perm-exact"
