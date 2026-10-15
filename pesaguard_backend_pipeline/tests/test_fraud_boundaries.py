"""Risk / fraud boundary regression tests (services 56-61).

Two things are pinned here:

1. Rule #28 -- fraud detection must distinguish anomaly / suspicion / high-risk /
   confirmed fraud and must NEVER auto-declare fraud from a score alone. The fraud
   engine's highest action is ``escalate`` (human review); there is no
   ``confirm_fraud`` action, and a high score alone never produces a "fraud"
   verdict. A busy merchant is not automatically fraud.

2. Defense-in-depth -- both the primary ingestion normalizer and the secondary
   anomaly scoring entry point independently reject non-finite amounts. Invalid
   financial data remains visible even if scoring is called outside ingestion.

Behaviour verified against the current implementation before being written here.
"""

from __future__ import annotations

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


def test_fraud_scoring_rejects_nonfinite_amounts():
    with pytest.raises(NormalizationError):
        assess_transaction(_tx("nan-amt", amount="nan"))


# --- Defense-in-depth: anomaly engine rejects non-finite amounts ----------------


def test_anomaly_engine_primary_path_rejects_non_finite():
    # The ingestion normalizer must reject the same non-finite values as scoring.
    for value in ("nan", "inf", "-inf", "NaN", "Infinity"):
        with pytest.raises(NormalizationError):
            normalize_amount(value)


def test_anomaly_engine_classifies_invalid_negative_and_nonfinite_amounts():
    assert "invalid_or_zero_amount" in check_for_anomalies({"TransID": "NEG", "TransAmount": "-5"}, set())
    assert "invalid_or_zero_amount" in check_for_anomalies({"TransID": "INF", "TransAmount": "inf"}, set())
    assert "invalid_or_zero_amount" in check_for_anomalies({"TransID": "NAN", "TransAmount": "nan"}, set())
    for value in ("nan", "inf", "-inf"):
        with pytest.raises(NormalizationError):
            score_transaction_anomaly({"TransID": "BAD", "TransAmount": value})
