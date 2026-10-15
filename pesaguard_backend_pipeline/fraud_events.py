"""Versioned fraud event publication on top of the existing risk-scoring pipeline.

The fraud risk engine and reconciliation job already compute and persist
decisions.  These helpers expose the same results as versioned Kafka events
without changing the durable scoring/assessment boundary: callers persist first
(``persist_assessment``), then publish the corresponding event.
"""
from __future__ import annotations

import logging
from typing import Any, Callable, Mapping, Optional

from fraud_risk_engine import MODEL_VERSION, RiskDecision

logger = logging.getLogger("pesaguard.fraud_events")


def _publish(event_type: str, tenant_id: str, aggregate_id: str, payload: Mapping[str, Any], publisher: Callable[..., Any], *, correlation_id: str | None = None) -> Any:
    from event_bus import build_event
    from producer import publish_versioned_event

    event = build_event(
        event_type,
        tenant_id,
        aggregate_id,
        dict(payload),
        correlation_id=correlation_id,
        producer="pesaguard.fraud_events",
        producer_version="1",
    )
    return publish_versioned_event(event, producer=publisher)


def analysis_event_type(action: str) -> str:
    """Map a risk-decision action to its registered fraud event type (Rule #28).

    ``process``/``monitor`` decisions complete the analysis lifecycle; a
    ``review`` raises an anomaly signal and an ``escalate`` creates a decision
    for human review. No action ever declares fraud by itself.
    """
    action = str(action or "").lower()
    if action == "review":
        return "fraud.anomaly_detected"
    if action == "escalate":
        return "fraud.decision_created"
    return "fraud.analysis.completed"


def analysis_payload(
    transaction_id: str,
    decision: RiskDecision,
    model_version: str = MODEL_VERSION,
) -> dict[str, Any]:
    """Serialize the explainable decision carried by every fraud event."""
    return {
        "transaction_id": transaction_id,
        "risk_score": decision.risk_score,
        "risk_level": decision.risk_level,
        "action": decision.action,
        "model_version": model_version,
        "features": dict(decision.features.as_dict()),
        "reason_codes": list(decision.reason_codes),
        "rules_triggered": list(decision.rules_triggered),
    }


def build_fraud_analysis_event(
    *,
    tenant_id: str,
    transaction_id: str,
    decision: RiskDecision,
    model_version: str = MODEL_VERSION,
    correlation_id: str | None = None,
) -> Any:
    """Build the versioned envelope for one fraud-analysis result."""
    from event_bus import build_event

    return build_event(
        analysis_event_type(getattr(decision, "action", "")),
        tenant_id,
        transaction_id,
        analysis_payload(transaction_id, decision, model_version),
        correlation_id=correlation_id,
        producer="pesaguard.fraud_events",
        producer_version="1",
    )


def publish_fraud_analysis(
    *,
    tenant_id: str,
    transaction_id: str,
    decision: RiskDecision,
    model_version: str = MODEL_VERSION,
    publisher: Callable[..., Any],
    correlation_id: str | None = None,
) -> Any:
    """Publish one self-describing fraud-analysis completion/result event."""
    event = build_fraud_analysis_event(
        tenant_id=tenant_id,
        transaction_id=transaction_id,
        decision=decision,
        model_version=model_version,
        correlation_id=correlation_id,
    )
    from producer import publish_versioned_event

    return publish_versioned_event(event, producer=publisher)


def stage_fraud_analysis_event(
    session: Any,
    *,
    tenant_id: str,
    transaction_id: str,
    decision: RiskDecision,
    event_key: str,
    model_version: str = MODEL_VERSION,
    correlation_id: str | None = None,
) -> Any:
    """Stage the versioned fraud event in the durable outbox.

    The row joins the caller's open transaction, so the persisted assessment
    and its publication intent commit (or roll back) together. Staging never
    commits: the caller owns the transaction boundary, and the existing outbox
    drain publishes the row with retries and dead-letter handling (Rule #8/#10).
    """
    import uuid
    from datetime import datetime, timezone

    from data_protection import protect_payload
    from models import TransactionOutbox
    from topics import EVENT_TYPE_TOPICS

    event = build_fraud_analysis_event(
        tenant_id=tenant_id,
        transaction_id=transaction_id,
        decision=decision,
        model_version=model_version,
        correlation_id=correlation_id,
    )
    now = datetime.now(timezone.utc)
    row = TransactionOutbox(
        id=f"outbox_{uuid.uuid4().hex[:12]}",
        tenant_id=tenant_id,
        event_key=event_key,
        topic=EVENT_TYPE_TOPICS[event.event_type],
        payload=protect_payload(event.to_dict()),
        status="pending",
        available_at=now,
        created_at=now,
    )
    session.add(row)
    return row


def publish_fraud_analysis_started(
    *,
    tenant_id: str,
    transaction_id: str,
    model_version: str = MODEL_VERSION,
    publisher: Callable[..., Any],
    correlation_id: str | None = None,
) -> Any:
    """Publish the auditable start of a fraud-analysis run."""
    return _publish(
        "fraud.analysis.started",
        tenant_id,
        transaction_id,
        {"transaction_id": transaction_id, "model_version": model_version},
        publisher,
        correlation_id=correlation_id,
    )


def publish_fraud_review_decision(
    *,
    tenant_id: str,
    transaction_id: str,
    reviewer: str,
    decision: RiskDecision,
    verdict: str,
    note: str | None = None,
    publisher: Callable[..., Any],
    correlation_id: str | None = None,
) -> Any:
    """Publish an analyst-attributed fraud review decision."""
    payload = {
        "transaction_id": transaction_id,
        "reviewer": reviewer,
        "verdict": verdict,
        "note": note or "",
        "risk_score": decision.risk_score,
        "risk_level": decision.risk_level,
        "model_version": decision.model_version,
        "reason_codes": list(decision.reason_codes),
        "rules_triggered": list(decision.rules_triggered),
    }
    return _publish("fraud.anomaly.reviewed", tenant_id, transaction_id, payload, publisher, correlation_id=correlation_id)
