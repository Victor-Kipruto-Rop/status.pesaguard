"""Risk-event emission, investigation workflow, and false-positive tracking.

Turns explainable ``RiskDecision`` outputs into durable ``RiskEvent`` rows and
provides a tenant-scoped, audit-logged investigation lifecycle:

    open -> investigating -> resolved (confirmed_fraud | inconclusive)
                          -> dismissed (false_positive)

Design invariants:
- Emission is idempotent: an indicator never re-emits while an open or
  investigating event of the same type already exists for the transaction.
- Evidence is PII-minimised: reasons are count-based and raw features
  (customer/merchant identifiers) are never copied into event evidence.
- Every investigation transition is appended to the AuditEvent ledger with
  actor attribution; outcomes are never overwritten silently.
"""

from __future__ import annotations

import hashlib
import json
import logging
import uuid
from datetime import datetime, timezone
from typing import Any, Mapping

from sqlalchemy.exc import IntegrityError

from fraud_risk_engine import RiskDecision, risk_priority
from models import AuditEvent, RiskEvent

logger = logging.getLogger("pesaguard.risk_indicators")

# Rule code -> RiskEvent.event_type. Unknown rule codes fall back to the raw
# code so a new indicator can never be silently dropped.
EVENT_TYPE_BY_RULE: dict[str, str] = {
    "velocity": "velocity_anomaly",
    "amount_threshold": "unusual_amount",
    "duplicate_activity": "duplicate_transaction",
    "repeated_reference": "repeated_reference",
    "processing_failure_pattern": "repeated_failure",
    "unusual_time": "unusual_time",
    "rapid_transaction_changes": "rapid_amount_changes",
    "correlated_indicators": "correlated_indicators",
}

OPEN_STATUSES = ("open", "investigating")
CLOSED_STATUSES = ("resolved", "dismissed")

VALID_OUTCOMES = ("confirmed_fraud", "false_positive", "inconclusive")
_OUTCOME_TO_STATUS = {
    "confirmed_fraud": "resolved",
    "inconclusive": "resolved",
    "false_positive": "dismissed",
}

_NOTE_MAX_LENGTH = 2000


class RiskEventNotFound(LookupError):
    """The tenant-scoped risk event does not exist."""


class InvalidInvestigationTransition(ValueError):
    """The requested transition is not valid for the event's current status."""


def _utc_now() -> datetime:
    return datetime.now(timezone.utc)


def _clean_note(note: Any) -> str:
    if note is None:
        return ""
    if not isinstance(note, str):
        raise ValueError("note must be a string")
    return note.strip()[:_NOTE_MAX_LENGTH]


def _require_tenant(tenant_id: Any) -> str:
    candidate = str(tenant_id or "").strip()
    if not candidate:
        raise ValueError("tenant_id is required")
    return candidate


def _append_audit(
    session: Any,
    *,
    tenant_id: str,
    event_type: str,
    event_id: str,
    actor: str,
    details: Mapping[str, Any],
) -> AuditEvent:
    payload = {"event_type": event_type, "actor": actor, "details": dict(details)}
    record = AuditEvent(
        id=f"audit_{uuid.uuid4().hex}",
        tenant_id=tenant_id,
        event_key=f"risk_event:{uuid.uuid4().hex}",
        event_type=event_type,
        aggregate_type="risk_event",
        aggregate_id=event_id,
        actor=actor,
        payload_hash=hashlib.sha256(
            json.dumps(payload, sort_keys=True, default=str).encode("utf-8")
        ).hexdigest(),
        details=dict(details),
        created_at=_utc_now(),
    )
    session.add(record)
    return record



def emit_risk_events(
    session: Any,
    *,
    tenant_id: str,
    decision: RiskDecision,
    transaction_id: str,
) -> list[RiskEvent]:
    """Persist one explainable RiskEvent per triggered indicator.

    Idempotent per (tenant, transaction, event_type): while an open or
    investigating event exists the indicator is not re-emitted, so provider
    retries and repeated analyses cannot flood the queue or reset an
    investigation. Returns only newly created events.
    """
    scoped_tenant = _require_tenant(tenant_id)
    transaction_ref = str(transaction_id or "").strip()
    if not transaction_ref:
        raise ValueError("transaction_id is required")

    created: list[RiskEvent] = []
    for code in decision.rules_triggered:
        event_type = EVENT_TYPE_BY_RULE.get(code, code)
        existing = (
            session.query(RiskEvent.id)
            .filter(
                RiskEvent.tenant_id == scoped_tenant,
                RiskEvent.transaction_id == transaction_ref,
                RiskEvent.event_type == event_type,
                RiskEvent.status.in_(OPEN_STATUSES),
            )
            .first()
        )
        if existing is not None:
            continue

        # Cross-event correlation: link other open events for the same
        # transaction so analysts can see the corroborating signal cluster.
        correlated_rows = (
            session.query(RiskEvent.id)
            .filter(
                RiskEvent.tenant_id == scoped_tenant,
                RiskEvent.transaction_id == transaction_ref,
                RiskEvent.status.in_(OPEN_STATUSES),
            )
            .all()
        )
        correlated_ids = [row.id for row in correlated_rows]

        reason = decision.rule_reasons.get(code, code)
        evidence: dict[str, Any] = {
            "rule_code": code,
            "reason": reason,
            "action": decision.action,
            "risk_level": decision.risk_level,
            "risk_score": float(decision.risk_score),
            "priority": risk_priority(decision.risk_level, decision.rules_triggered),
            "rules_triggered": list(decision.rules_triggered),
            "model_version": decision.model_version,
            "detected_at": decision.detected_at.isoformat(),
            "correlated_event_ids": correlated_ids,
        }
        event = RiskEvent(
            id=f"risk_{uuid.uuid4().hex}",
            tenant_id=scoped_tenant,
            event_type=event_type,
            risk_level=decision.risk_level,
            risk_score=decision.risk_score,
            transaction_id=transaction_ref,
            description=reason[:500],
            evidence=evidence,
            status="open",
            created_at=_utc_now(),
        )
        try:
            with session.begin_nested():
                session.add(event)
        except IntegrityError:
            # Concurrent emission raced a uniqueness guard; the savepoint
            # rolled back only this insert, the existing event stands.
            logger.info(
                "skipped duplicate risk event tenant=%s transaction=%s type=%s",
                scoped_tenant,
                transaction_ref,
                event_type,
            )
            continue
        created.append(event)

    if created:
        session.flush()
        try:
            from metrics import record_business_metric

            record_business_metric("risk_events_emitted", len(created))
        except Exception:
            logger.debug("risk-event metric hook unavailable", exc_info=True)
    return created

def _get_event(session: Any, tenant_id: str, event_id: str) -> RiskEvent:
    event = (
        session.query(RiskEvent)
        .filter(
            RiskEvent.tenant_id == tenant_id,
            RiskEvent.id == event_id,
        )
        .one_or_none()
    )
    if event is None:
        raise RiskEventNotFound("risk event not found")
    return event

def start_investigation(
    session: Any,
    *,
    tenant_id: str,
    event_id: str,
    actor: str,
    note: str = "",
) -> RiskEvent:
    """Transition an open risk event to investigating with audit attribution."""
    scoped_tenant = _require_tenant(tenant_id)
    clean_note = _clean_note(note)
    actor_name = str(actor or "unknown").strip() or "unknown"
    event = _get_event(session, scoped_tenant, event_id)
    if event.status != "open":
        raise InvalidInvestigationTransition(
            f"risk event cannot move from '{event.status}' to 'investigating'"
        )

    event.status = "investigating"
    evidence = dict(event.evidence or {})
    investigation = dict(evidence.get("investigation") or {})
    investigation.update(
        {
            "started_by": actor_name,
            "started_at": _utc_now().isoformat(),
            "note": clean_note,
        }
    )
    evidence["investigation"] = investigation
    event.evidence = evidence
    _append_audit(
        session,
        tenant_id=scoped_tenant,
        event_type="risk_event.investigation_started",
        event_id=event.id,
        actor=actor_name,
        details={"from_status": "open", "to_status": "investigating", "note": clean_note},
    )
    session.flush()
    return event


def record_outcome(
    session: Any,
    *,
    tenant_id: str,
    event_id: str,
    actor: str,
    outcome: str,
    note: str = "",
) -> RiskEvent:
    """Record a terminal investigation outcome with audit attribution.

    ``false_positive`` dismisses the event (false-positive tracking);
    ``confirmed_fraud`` and ``inconclusive`` resolve it. Terminal events are
    immutable: re-resolution raises ``InvalidInvestigationTransition``.
    """
    scoped_tenant = _require_tenant(tenant_id)
    clean_note = _clean_note(note)
    actor_name = str(actor or "unknown").strip() or "unknown"
    if outcome not in _OUTCOME_TO_STATUS:
        raise ValueError(f"outcome must be one of: {', '.join(VALID_OUTCOMES)}")
    event = _get_event(session, scoped_tenant, event_id)
    if event.status in CLOSED_STATUSES:
        raise InvalidInvestigationTransition(
            f"risk event is already closed with status '{event.status}'"
        )

    from_status = event.status
    new_status = _OUTCOME_TO_STATUS[outcome]
    now = _utc_now()
    event.status = new_status
    event.resolved_at = now
    event.resolution_note = clean_note or outcome
    evidence = dict(event.evidence or {})
    investigation = dict(evidence.get("investigation") or {})
    investigation.update(
        {
            "outcome": outcome,
            "resolved_by": actor_name,
            "resolved_at": now.isoformat(),
            "note": clean_note,
        }
    )
    evidence["investigation"] = investigation
    event.evidence = evidence
    _append_audit(
        session,
        tenant_id=scoped_tenant,
        event_type="risk_event.outcome_recorded",
        event_id=event.id,
        actor=actor_name,
        details={
            "from_status": from_status,
            "to_status": new_status,
            "outcome": outcome,
            "note": clean_note,
        },
    )
    session.flush()
    try:
        from metrics import record_business_metric

        record_business_metric(f"risk_outcome_{outcome}")
    except Exception:
        logger.debug("risk-outcome metric hook unavailable", exc_info=True)
    return event


def investigation_audit_trail(
    session: Any,
    *,
    tenant_id: str,
    event_id: str,
) -> list[dict[str, Any]]:
    """Append-only investigation audit entries for one tenant-scoped event."""
    scoped_tenant = _require_tenant(tenant_id)
    _get_event(session, scoped_tenant, event_id)  # tenant-scoped existence check
    rows = (
        session.query(AuditEvent)
        .filter(
            AuditEvent.tenant_id == scoped_tenant,
            AuditEvent.aggregate_type == "risk_event",
            AuditEvent.aggregate_id == event_id,
        )
        .order_by(AuditEvent.created_at.asc())
        .all()
    )
    return [
        {
            "id": row.id,
            "event_type": row.event_type,
            "actor": row.actor,
            "details": row.details,
            "created_at": row.created_at.isoformat() if row.created_at else None,
        }
        for row in rows
    ]


def false_positive_metrics(session: Any, tenant_id: str) -> dict[str, Any]:
    """False-positive and investigation outcome metrics for one tenant.

    Counts are derived from persisted event status and the recorded
    investigation outcome so the numbers always reconcile with the audit
    trail; the false-positive rate is over closed investigations only.
    """
    scoped_tenant = _require_tenant(tenant_id)
    rows = (
        session.query(RiskEvent.status, RiskEvent.evidence)
        .filter(RiskEvent.tenant_id == scoped_tenant)
        .all()
    )
    by_status = {"open": 0, "investigating": 0, "resolved": 0, "dismissed": 0}
    by_outcome = {outcome: 0 for outcome in VALID_OUTCOMES}
    pending = 0
    for status, evidence in rows:
        if status in by_status:
            by_status[status] += 1
        outcome = str(((evidence or {}).get("investigation") or {}).get("outcome") or "").strip()
        if outcome in by_outcome:
            by_outcome[outcome] += 1
        elif status in OPEN_STATUSES:
            pending += 1
    closed = sum(by_outcome.values())
    return {
        "tenant_id": scoped_tenant,
        "total_events": len(rows),
        "by_status": by_status,
        "by_outcome": by_outcome,
        "pending": pending,
        "false_positives": by_outcome["false_positive"],
        "confirmed_fraud": by_outcome["confirmed_fraud"],
        "inconclusive": by_outcome["inconclusive"],
        "false_positive_rate": by_outcome["false_positive"] / closed if closed else 0.0,
    }
