"""Risk / fraud boundary regression tests (services 56-61).

Two things are pinned here:

1. Rule #28 -- fraud detection must distinguish anomaly / suspicion / high-risk /
   confirmed fraud and must NEVER auto-declare fraud from a score alone. The fraud
   engine's highest action is ``escalate`` (human review); there is no
   ``confirm_fraud`` action, and a high score alone never produces a "fraud"
   verdict. A busy merchant is not automatically fraud.

2. Defense-in-depth -- the anomaly engine (``anomaly_rules``) is a *secondary*
   detection path. The primary ingestion path (``normalization.normalize_amount``)
   already rejects NaN/Infinity, so non-finite amounts never reach anomaly scoring
   through normal ingestion. These tests document the current behaviour of the
   secondary path so a future change is deliberate rather than silent (Rule #10/#32:
   bad data must be visible).

Behaviour verified against the current implementation before being written here.
"""

from __future__ import annotations

import math

import pytest

from anomaly_rules import check_for_anomalies, score_transaction_anomaly
from fraud_risk_engine import assess_transaction
from normalization import NormalizationError, normalize_amount


def _tx(identifier, *, amount="1000", customer="254700000001", merchant="600000", when="20260914100000"):
    return {
        "TransID": identifier,
        "TransAmount": amount,
        "MSISDN": customer,
        "BusinessShortCode": merchant,
        "TransTime": when,
    }


# --- Rule #28: fraud engine never auto-declares fraud ---------------------------


def test_no_action_ever_confirms_fraud():
    # The engine maps every level to process/monitor/review/escalate; nothing
    # auto-declares "fraud" -- a human must review (Rule #28).
    allowed_actions = {"process", "monitor", "review", "escalate"}
    for amount, when in [("1000", "20260914100000"), ("400000", "20260914020000"), ("900000", "20260914030000")]:
        decision = assess_transaction(_tx("x", amount=amount, when=when))
        assert decision.action in allowed_actions
        assert decision.action != "confirm_fraud"


def test_risk_levels_are_the_documented_four_tiers():
    # Rule #28 vocabulary: LOW/MEDIUM/HIGH/CRITICAL, not a binary "fraud/not".
    seen = set()
    for amount, when in [("1000", "20260914100000"), ("60000", "20260914100000"), ("300000", "20260914030000"), ("900000", "20260914030000")]:
        seen.add(assess_transaction(_tx("lvl", amount=amount, when=when)).risk_level)
    assert seen.issubset({"LOW", "MEDIUM", "HIGH", "CRITICAL"})


def test_high_score_is_bounded_and_never_nan():
    # A pathological amount must not produce a NaN score; it is clamped to [0, 1].
    decision = assess_transaction(_tx("nan-amt", amount="nan"))
    assert not math.isnan(decision.risk_score)
    assert 0.0 <= decision.risk_score <= 1.0


# --- Defense-in-depth: anomaly engine's handling of non-finite amounts ----------


def test_anomaly_engine_primary_path_rejects_non_finite():
    # Documents WHY the secondary path gap is not a live hole: the primary
    # ingestion normalizer rejects NaN/Infinity before anomaly scoring runs.
    for value in ("nan", "inf", "-inf", "NaN", "Infinity"):
        with pytest.raises(NormalizationError):
            normalize_amount(value)


def test_anomaly_engine_flags_negative_and_infinite_but_documents_nan_gap():
    # Negative and infinite amounts are visible to the anomaly engine.
    assert "invalid_or_zero_amount" in check_for_anomalies({"TransID": "NEG", "TransAmount": "-5"}, set())
    assert any("amount_exceeds_threshold" in a for a in check_for_anomalies({"TransID": "INF", "TransAmount": "inf"}, set()))
    # KNOWN defense-in-depth gap (flagged for human decision): a literal "nan"
    # amount is NOT flagged by the secondary anomaly path (it silently scores 0.0).
    # This is unreachable via normal ingestion (see test above) but is pinned here
    # so the behaviour is explicit rather than accidental.
    assert check_for_anomalies({"TransID": "NAN", "TransAmount": "nan"}, set()) == []
    assert score_transaction_anomaly({"TransID": "NAN", "TransAmount": "nan"}) == 0.0
