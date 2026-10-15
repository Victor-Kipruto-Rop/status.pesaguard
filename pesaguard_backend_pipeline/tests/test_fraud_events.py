"""Risk Event Service (61): versioned fraud event publication and durable staging.

Two publication paths are pinned here:

1. Direct publication helpers (``publish_fraud_analysis`` /
   ``publish_fraud_review_decision``) route each decision to the registered
   topic and carry the explainable decision payload (Rule #28: the event
   describes score/level/reasons, never an automatic fraud verdict).

2. ``stage_fraud_analysis_event`` writes the same envelope into the durable
   ``transaction_outbox`` inside the caller's transaction so the
   ``/fraud/analyse`` route publishes atomically with the persisted
   assessment (Rule #8/#10: no silently dropped risk events).
"""

from __future__ import annotations

import pytest
from sqlalchemy import create_engine
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import sessionmaker
from sqlalchemy.pool import StaticPool

from data_protection import unprotect_payload
from event_bus import validate_event
from fraud_events import (
    publish_fraud_analysis,
    publish_fraud_review_decision,
    stage_fraud_analysis_event,
)
from fraud_risk_engine import FraudFeatures, RiskDecision
from models import Base, TransactionOutbox
from topics import (
    TOPIC_FRAUD_ANALYSIS,
    TOPIC_FRAUD_ANOMALIES,
    TOPIC_FRAUD_DECISIONS,
)


def _features(amount: float = 1000.0) -> FraudFeatures:
    return FraudFeatures(
        transaction_frequency=0.1,
        amount_deviation=0.2,
        velocity=0.0,
        customer_history=0.5,
        tenant_baseline=0.1,
        time_pattern=0.0,
        amount=amount,
        customer_id="254700000001",
        merchant_id="600000",
    )


def _decision(action: str, risk_level: str = "LOW", risk_score: float = 0.1) -> RiskDecision:
    return RiskDecision(
        risk_score=risk_score,
        risk_level=risk_level,
        action=action,
        reason_codes=("amount_threshold",),
        model_version="fraud-rules-v1",
        rules_triggered=("velocity",),
        features=_features(),
    )


class CapturingPublisher:
    def __init__(self) -> None:
        self.messages: list[dict] = []

    def send(self, topic, key=None, value=None, headers=None):
        self.messages.append({"topic": topic, "key": key, "value": value, "headers": headers})
        return self


def _build_session():
    engine = create_engine(
        "sqlite:///:memory:",
        connect_args={"check_same_thread": False},
        poolclass=StaticPool,
    )
    Base.metadata.create_all(engine)
    return engine, sessionmaker(bind=engine, expire_on_commit=False)()


# --- Direct publication -------------------------------------------------------


def test_process_decision_publishes_to_the_fraud_analysis_topic():
    publisher = CapturingPublisher()
    publish_fraud_analysis(
        tenant_id="tenant-a",
        transaction_id="tx-1",
        decision=_decision("process"),
        publisher=publisher,
    )
    assert len(publisher.messages) == 1
    message = publisher.messages[0]
    assert message["topic"] == TOPIC_FRAUD_ANALYSIS
    assert message["value"]["event_type"] == "fraud.analysis.completed"
    assert message["value"]["tenant_id"] == "tenant-a"
    assert message["value"]["aggregate_id"] == "tx-1"


def test_review_decision_maps_to_the_fraud_anomaly_topic():
    publisher = CapturingPublisher()
    publish_fraud_analysis(
        tenant_id="tenant-a",
        transaction_id="tx-2",
        decision=_decision("review", risk_level="HIGH", risk_score=0.7),
        publisher=publisher,
    )
    assert publisher.messages[0]["topic"] == TOPIC_FRAUD_ANOMALIES
    assert publisher.messages[0]["value"]["event_type"] == "fraud.anomaly_detected"


def test_escalate_decision_maps_to_the_fraud_decision_topic():
    publisher = CapturingPublisher()
    publish_fraud_analysis(
        tenant_id="tenant-a",
        transaction_id="tx-3",
        decision=_decision("escalate", risk_level="CRITICAL", risk_score=0.95),
        publisher=publisher,
    )
    assert publisher.messages[0]["topic"] == TOPIC_FRAUD_DECISIONS
    assert publisher.messages[0]["value"]["event_type"] == "fraud.decision_created"


def test_analysis_event_payload_carries_the_explainable_decision():
    publisher = CapturingPublisher()
    publish_fraud_analysis(
        tenant_id="tenant-a",
        transaction_id="tx-4",
        decision=_decision("review", risk_level="HIGH", risk_score=0.7),
        publisher=publisher,
    )
    payload = publisher.messages[0]["value"]["payload"]
    assert payload["transaction_id"] == "tx-4"
    assert payload["risk_score"] == 0.7
    assert payload["risk_level"] == "HIGH"
    assert payload["model_version"] == "fraud-rules-v1"
    assert payload["reason_codes"] == ["amount_threshold"]
    assert payload["rules_triggered"] == ["velocity"]
    assert payload["features"]["amount"] == 1000.0


def test_review_decision_event_is_analyst_attributed():
    publisher = CapturingPublisher()
    publish_fraud_review_decision(
        tenant_id="tenant-a",
        transaction_id="tx-5",
        reviewer="analyst-1",
        decision=_decision("review", risk_level="HIGH", risk_score=0.7),
        verdict="dismissed",
        note="expected business pattern",
        publisher=publisher,
    )
    message = publisher.messages[0]
    assert message["topic"] == TOPIC_FRAUD_ANOMALIES
    assert message["value"]["event_type"] == "fraud.anomaly.reviewed"
    payload = message["value"]["payload"]
    assert payload["reviewer"] == "analyst-1"
    assert payload["verdict"] == "dismissed"
    assert payload["note"] == "expected business pattern"


# --- Durable staging (outbox) -------------------------------------------------


def test_staged_event_is_a_pending_outbox_row_in_the_callers_transaction():
    engine, session = _build_session()
    row = stage_fraud_analysis_event(
        session,
        tenant_id="tenant-a",
        transaction_id="tx-6",
        decision=_decision("review", risk_level="HIGH", risk_score=0.7),
        event_key="fraud-analysis:key-6",
    )
    assert row.status == "pending"
    assert row.topic == TOPIC_FRAUD_ANOMALIES
    assert row.tenant_id == "tenant-a"
    assert row.event_key == "fraud-analysis:key-6"

    # Staging never commits: rolling back the caller's transaction removes it.
    session.rollback()
    with sessionmaker(bind=engine)() as verify:
        assert verify.query(TransactionOutbox).count() == 0
    session.close()
    engine.dispose()


def test_committed_staged_event_round_trips_as_a_valid_envelope():
    engine, session = _build_session()
    stage_fraud_analysis_event(
        session,
        tenant_id="tenant-a",
        transaction_id="tx-7",
        decision=_decision("process"),
        event_key="fraud-analysis:key-7",
    )
    session.commit()

    with sessionmaker(bind=engine)() as verify:
        row = verify.query(TransactionOutbox).one()
        assert row.topic == TOPIC_FRAUD_ANALYSIS
        envelope = validate_event(unprotect_payload(row.payload))
        assert envelope.event_type == "fraud.analysis.completed"
        assert envelope.tenant_id == "tenant-a"
        assert envelope.aggregate_id == "tx-7"
        assert envelope.payload["transaction_id"] == "tx-7"
    session.close()
    engine.dispose()


def test_duplicate_event_keys_are_rejected_by_the_database():
    engine, session = _build_session()
    stage_fraud_analysis_event(
        session,
        tenant_id="tenant-a",
        transaction_id="tx-8",
        decision=_decision("process"),
        event_key="fraud-analysis:same-key",
    )
    session.flush()
    duplicate = stage_fraud_analysis_event(
        session,
        tenant_id="tenant-a",
        transaction_id="tx-8",
        decision=_decision("process"),
        event_key="fraud-analysis:same-key",
    )
    with pytest.raises(IntegrityError):
        session.flush()
    assert duplicate.event_key == "fraud-analysis:same-key"
    session.close()
    engine.dispose()

