"""
Basic tests for anomaly detection rules.
Run with: pytest tests/
"""
import os
import sys

sys.path.append(os.path.join(os.path.dirname(__file__), "..", "streaming", "flink_jobs"))

from anomaly_rules import check_for_anomalies, score_transaction_anomaly  # noqa: E402


def test_clean_transaction_has_no_anomalies():
    event = {"TransID": "ABC123", "TransAmount": "500"}
    result = check_for_anomalies(event, seen_trans_ids=set())
    assert result == []


def test_scoring_computes_a_bounded_score_for_large_amounts():
    # Regression: the extreme-amount tier multiplied a Decimal threshold by a
    # float, which raised TypeError and degraded every scoring run to
    # "anomaly_evaluation_error" instead of producing a score.
    event = {"TransID": "BIG1", "TransAmount": "260123", "TransTime": "20260715010000"}
    score = score_transaction_anomaly(event)
    assert 0.0 <= score <= 1.0
    assert score >= 0.8
    anomalies = check_for_anomalies(event, seen_trans_ids=set())
    assert any(item.startswith("high_anomaly_score") for item in anomalies)
    assert "anomaly_evaluation_error" not in anomalies



def test_duplicate_transaction_flagged():
    event = {"TransID": "ABC123", "TransAmount": "500"}
    result = check_for_anomalies(event, seen_trans_ids={"ABC123"})
    assert "duplicate_transaction_id" in result


def test_large_amount_flagged():
    event = {"TransID": "XYZ999", "TransAmount": "200000"}
    result = check_for_anomalies(event, seen_trans_ids=set())
    assert any("amount_exceeds_threshold" in a for a in result)


def test_zero_amount_flagged():
    event = {"TransID": "ZERO1", "TransAmount": "0"}
    result = check_for_anomalies(event, seen_trans_ids=set())
    assert "invalid_or_zero_amount" in result


def test_invalid_amount_type_flagged():
    event = {"TransID": "BAD1", "TransAmount": "not_a_number"}
    result = check_for_anomalies(event, seen_trans_ids=set())
    assert "invalid_or_zero_amount" in result
