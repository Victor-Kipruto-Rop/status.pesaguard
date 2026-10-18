import logging
import hashlib
import json
import re
import uuid
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional

from flask import Blueprint, current_app, g, jsonify, request
from werkzeug.local import LocalProxy
from sqlalchemy.orm import Session

from auth_rbac import get_current_user, require_auth
from alert_center import append_alert_event, queue_alert_email_deliveries, record_alert
from event_store import EventStore
from models import Alert, AlertDelivery, AlertEvent, AuditEvent, EscalationRule, OnCallRotation
from rate_limiter import rate_limit
from tenant_context import current_tenant_id

bp = Blueprint("alert_center_v2", __name__, url_prefix="/api/v1")

event_store = EventStore()
session = LocalProxy(lambda: g.alert_session)


@bp.before_request
def _ensure_request_session():
    """Create a fresh SQLAlchemy session for each request to this blueprint."""
    store = current_app.extensions.get("pesaguard_event_store", event_store)
    store._ensure_ready()
    g.alert_session = store.Session()


@bp.teardown_request
def _close_request_session(_exc=None):
    """Close the per-request session after the response is sent."""
    request_session = g.pop("alert_session", None)
    if request_session is not None:
        if _exc is not None:
            request_session.rollback()
        request_session.close()

_log = logging.getLogger("pesaguard.alert.routes")

_ALLOWED_CHANNELS = {"in_app", "email", "slack", "sms", "webhook"}
_ALLOWED_SEVERITIES = {"info", "warning", "critical"}
_ALLOWED_STATUSES = {"open", "acknowledged", "resolved", "suppressed"}
_ALLOWED_OUTCOMES = {"confirmed_fraud", "false_positive", "inconclusive"}

TENANT_ID_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")


def _alert_to_dict(alert: Alert, *, include_deliveries: bool = False) -> Dict[str, Any]:
    payload = {
        "id": alert.id,
        "tenant_id": alert.tenant_id,
        "alert_type": alert.alert_type,
        "category": alert.alert_type,
        "severity": alert.severity,
        "status": alert.status,
        "title": alert.title,
        "message": alert.message,
        "source": alert.source,
        "transaction_id": alert.transaction_id,
        "discrepancy_id": alert.discrepancy_id,
        "assigned_to": alert.assigned_to,
        "investigation_notes": alert.investigation_notes,
        "resolution_note": alert.resolution_note,
        "resolution_outcome": alert.resolution_outcome,
        "acknowledged_at": alert.acknowledged_at.isoformat() if alert.acknowledged_at else None,
        "acknowledged_by": alert.acknowledged_by,
        "resolved_at": alert.resolved_at.isoformat() if alert.resolved_at else None,
        "created_at": alert.created_at.isoformat(),
        "updated_at": alert.updated_at.isoformat(),
        "last_seen_at": alert.last_seen_at.isoformat() if alert.last_seen_at else None,
        "duplicate_count": int(alert.duplicate_count or 0),
        "request_id": alert.request_id,
    }
    if include_deliveries:
        payload["deliveries"] = _delivery_payload(alert)
    return payload


def _delivery_payload(alert: Alert) -> list[Dict[str, Any]]:
    from communications.models import CommunicationAttempt, CommunicationNotification

    rows = session.query(AlertDelivery).filter_by(
        tenant_id=alert.tenant_id,
        alert_id=alert.id,
    ).order_by(AlertDelivery.created_at.asc()).all()
    result = []
    for row in rows:
        status = row.status
        attempts = int(row.attempt_count or 0)
        error_code = row.error_code
        if row.communication_notification_id:
            notification = session.query(CommunicationNotification).filter_by(
                id=row.communication_notification_id,
                tenant_id=alert.tenant_id,
            ).one_or_none()
            if notification is not None:
                status = notification.status
                error_code = notification.failure_code or error_code
                attempts = session.query(CommunicationAttempt).filter_by(
                    notification_id=notification.id,
                    tenant_id=alert.tenant_id,
                ).count()
        result.append({
            "id": row.id,
            "channel": row.channel,
            "status": status,
            "attempt_count": attempts,
            "retry_generation": int(row.retry_generation or 0),
            "error_code": error_code,
            "created_at": row.created_at.isoformat(),
            "updated_at": row.updated_at.isoformat(),
        })
    return result


def _resolve_tenant():
    tenant_id = current_tenant_id()
    if not tenant_id:
        return None, (jsonify({"error": "tenant_context_required", "message": "Tenant context is required."}), 400)
    if not TENANT_ID_PATTERN.fullmatch(tenant_id):
        return None, (jsonify({"error": "invalid_tenant_id", "message": "Tenant ID is invalid."}), 400)
    return tenant_id, None


def _pagination_params():
    try:
        page = max(1, int(request.args.get("page", "1")))
    except (TypeError, ValueError):
        page = 1
    try:
        per_page = min(100, max(1, int(request.args.get("per_page", "20"))))
    except (TypeError, ValueError):
        per_page = 20
    return page, per_page


def _paginate(query, page, per_page):
    total = query.count()
    items = query.offset((page - 1) * per_page).limit(per_page).all()
    return items, total


def _parse_iso(value: Optional[str]) -> Optional[datetime]:
    if value is None:
        return None
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as exc:
        raise ValueError("timestamp must use ISO-8601 format") from exc
    if parsed.tzinfo is None:
        raise ValueError("timestamp must include a timezone")
    return parsed.astimezone(timezone.utc)


def _clean_text(value: Any) -> Optional[str]:
    if value is None:
        return None
    if not isinstance(value, str):
        value = str(value)
    return value.strip() or None


def _require_actor() -> str:
    user = get_current_user()
    actor = getattr(user, "username", None) or str(getattr(user, "user_id", None) or "anonymous")
    if not actor:
        raise PermissionError("unauthenticated actor")
    return actor


def _append_alert_audit(
    session: Session,
    *,
    tenant_id: str,
    alert_id: str,
    actor: str,
    action: str,
    details: Optional[Dict[str, Any]] = None,
) -> None:
    audit_details = {"alert_id": alert_id, **(details or {})}
    canonical_details = json.dumps(audit_details, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
    record = AuditEvent(
        id=f"audit_{uuid.uuid4().hex}",
        tenant_id=tenant_id,
        event_type=f"alert.{action}",
        aggregate_type="alert",
        aggregate_id=alert_id,
        actor=actor,
        payload_hash=hashlib.sha256(canonical_details.encode("utf-8")).hexdigest(),
        details=audit_details,
        event_key=f"alert:{tenant_id}:{alert_id}:{action}:{uuid.uuid4().hex}",
    )
    session.add(record)
    session.flush()

@bp.get("/alerts")
@require_auth(required_permission="read:discrepancies")
@rate_limit(max_requests_per_minute=60)
def list_alerts():
    """List tenant-scoped alerts with severity/status/type filtering and pagination."""
    tenant_id, err = _resolve_tenant()
    if err:
        return err

    page, per_page = _pagination_params()
    status = request.args.get("status")
    severity = request.args.get("severity")
    alert_type = request.args.get("category") or request.args.get("alert_type")
    try:
        created_after = _parse_iso(request.args.get("created_after"))
        created_before = _parse_iso(request.args.get("created_before"))
    except ValueError as exc:
        return jsonify({"error": "invalid_timestamp", "message": str(exc)}), 400

    query = session.query(Alert).filter(Alert.tenant_id == tenant_id)
    if status in _ALLOWED_STATUSES:
        query = query.filter(Alert.status == status)
    elif status:
        return jsonify({"error": "invalid_status", "message": f"status must be one of {_ALLOWED_STATUSES}"}), 400
    if severity in _ALLOWED_SEVERITIES:
        query = query.filter(Alert.severity == severity)
    elif severity:
        return jsonify({"error": "invalid_severity", "message": f"severity must be one of {_ALLOWED_SEVERITIES}"}), 400
    if alert_type:
        query = query.filter(Alert.alert_type == alert_type)
    assigned_to = request.args.get("assigned_to")
    if assigned_to:
        query = query.filter(Alert.assigned_to == assigned_to.strip())
    unread_only = request.args.get("unread_only", "false").strip().lower()
    if unread_only not in {"true", "false", "1", "0"}:
        return jsonify({"error": "invalid_unread_only", "message": "unread_only must be true or false."}), 400
    if unread_only in {"true", "1"}:
        query = query.filter(Alert.status == "open")
    if created_after is not None:
        query = query.filter(Alert.created_at >= created_after)
    if created_before is not None:
        query = query.filter(Alert.created_at <= created_before)

    total = query.count()
    items = (
        query.order_by(Alert.created_at.desc())
        .offset((page - 1) * per_page)
        .limit(per_page)
        .all()
    )

    return jsonify(
        {
            "items": [_alert_to_dict(a, include_deliveries=True) for a in items],
            "page": page,
            "per_page": per_page,
            "total": total,
        }
    ), 200


@bp.post("/alerts")
@require_auth(required_permission="write:discrepancies")
@rate_limit(max_requests_per_minute=30)
def create_alert():
    """Persist an event-triggered alert and queue email through the durable outbox."""
    tenant_id, err = _resolve_tenant()
    if err:
        return err
    payload = request.get_json(silent=True)
    if not isinstance(payload, dict):
        return jsonify({"error": "invalid_request", "message": "A JSON object is required."}), 400
    severity = str(payload.get("severity") or "").strip().lower()
    alert_type = str(payload.get("alert_type") or payload.get("category") or "").strip()
    title = str(payload.get("title") or "").strip()
    if severity not in _ALLOWED_SEVERITIES:
        return jsonify({"error": "invalid_severity", "message": f"severity must be one of {sorted(_ALLOWED_SEVERITIES)}"}), 400
    if not alert_type or len(alert_type) > 64:
        return jsonify({"error": "invalid_category", "message": "category is required and must be at most 64 characters."}), 400
    if not title or len(title) > 255:
        return jsonify({"error": "invalid_title", "message": "title is required and must be at most 255 characters."}), 400

    actor = _require_actor()
    event_id = payload.get("event_id") or request.headers.get("Idempotency-Key") or uuid.uuid4().hex
    if event_id is not None and (not isinstance(event_id, str) or not event_id.strip() or len(event_id) > 255):
        return jsonify({"error": "invalid_event_id", "message": "event_id must contain 1 to 255 characters."}), 400
    payload["event_id"] = event_id.strip()
    if not any(payload.get(key) for key in ("deduplication_key", "fingerprint", "id", "trans_id", "transaction_id", "event")):
        payload["deduplication_key"] = payload["event_id"]
    payload["alert_type"] = alert_type
    payload["request_id"] = request.headers.get("X-Request-ID") or request.headers.get("X-Correlation-ID")

    try:
        alert, created, replay = record_alert(session, tenant_id=tenant_id, payload=payload, actor=actor)
        if created or replay:
            queue_alert_email_deliveries(session, alert=alert)
        session.commit()
        response = _alert_to_dict(alert, include_deliveries=True)
        response["duplicate_suppressed"] = not created
        response["event_replay"] = replay
        return jsonify(response), 201 if created else 200
    except (TypeError, ValueError) as exc:
        session.rollback()
        return jsonify({"error": "invalid_alert", "message": str(exc)}), 400
    except Exception:
        session.rollback()
        _log.exception("Failed to persist alert event")
        return jsonify({"error": "alert_persistence_failed", "message": "The alert could not be persisted."}), 503


@bp.get("/alerts/<alert_id>")
@require_auth(required_permission="read:discrepancies")
@rate_limit(max_requests_per_minute=60)
def get_alert(alert_id: str):
    tenant_id, err = _resolve_tenant()
    if err:
        return err

    alert = (
        session.query(Alert)
        .filter(Alert.tenant_id == tenant_id, Alert.id == alert_id)
        .one_or_none()
    )
    if alert is None:
        return jsonify({"error": "not_found", "message": "Alert not found."}), 404

    return jsonify(_alert_to_dict(alert, include_deliveries=True)), 200


@bp.get("/alerts/<alert_id>/events")
@require_auth(required_permission="read:discrepancies")
@rate_limit(max_requests_per_minute=60)
def get_alert_events(alert_id: str):
    tenant_id, err = _resolve_tenant()
    if err:
        return err
    alert = session.query(Alert).filter_by(tenant_id=tenant_id, id=alert_id).one_or_none()
    if alert is None:
        return jsonify({"error": "not_found", "message": "Alert not found."}), 404
    page, per_page = _pagination_params()
    query = session.query(AlertEvent).filter_by(
        tenant_id=tenant_id,
        alert_id=alert_id,
    )
    total = query.count()
    events = query.order_by(AlertEvent.created_at.asc(), AlertEvent.id.asc()).offset(
        (page - 1) * per_page
    ).limit(per_page).all()
    return jsonify({"alert_id": alert_id, "items": [{
        "id": event.id,
        "event_type": event.event_type,
        "actor": event.actor,
        "from_status": event.from_status,
        "to_status": event.to_status,
        "details": event.details or {},
        "created_at": event.created_at.isoformat(),
    } for event in events], "page": page, "per_page": per_page, "total": total}), 200


@bp.post("/alerts/<alert_id>/deliveries/<delivery_id>/retry")
@require_auth(required_permission="write:discrepancies")
@rate_limit(max_requests_per_minute=20)
def retry_alert_delivery(alert_id: str, delivery_id: str):
    """Start a new bounded retry generation for a failed Slack or SMS delivery."""
    tenant_id, err = _resolve_tenant()
    if err:
        return err
    actor = _require_actor()
    alert = session.query(Alert).filter_by(tenant_id=tenant_id, id=alert_id).one_or_none()
    if alert is None:
        return jsonify({"error": "not_found", "message": "Alert not found."}), 404
    if alert.status in {"resolved", "suppressed"}:
        return jsonify({"error": "conflict", "message": f"Alert is already {alert.status}."}), 409
    delivery = session.query(AlertDelivery).filter_by(
        tenant_id=tenant_id,
        alert_id=alert_id,
        id=delivery_id,
    ).with_for_update().one_or_none()
    if delivery is None:
        return jsonify({"error": "not_found", "message": "Alert delivery not found."}), 404
    if delivery.channel not in {"slack", "sms"}:
        return jsonify({
            "error": "retry_not_supported",
            "message": "Email retries use the communications outbox replay workflow.",
        }), 409
    if delivery.communication_notification_id:
        return jsonify({
            "error": "retry_not_supported",
            "message": "This delivery is managed by the communications outbox.",
        }), 409
    if delivery.status not in {"failed", "dead_letter", "not_configured"}:
        return jsonify({"error": "conflict", "message": f"Delivery is not retryable from '{delivery.status}'."}), 409

    created_event = session.query(AlertEvent).filter_by(
        tenant_id=tenant_id,
        alert_id=alert_id,
        event_type="alert.created",
    ).order_by(AlertEvent.created_at.asc()).first()
    source_event_id = (created_event.details or {}).get("source_event_id") if created_event is not None else None
    if not source_event_id:
        return jsonify({
            "error": "retry_unavailable",
            "message": "The original source event ID is not available for safe replay.",
        }), 409
    deduplication_key = (created_event.details or {}).get("deduplication_key")

    previous_status = delivery.status
    previous_attempts = int(delivery.attempt_count or 0)
    delivery.retry_generation = int(delivery.retry_generation or 0) + 1
    delivery.status = "queued"
    delivery.error_code = None
    delivery.last_error = None
    delivery.updated_at = datetime.now(timezone.utc)
    append_alert_event(
        session,
        alert,
        event_type="alert.delivery_retry_requested",
        actor=actor,
        details={
            "channel": delivery.channel,
            "delivery_id": delivery.id,
            "from_status": previous_status,
            "prior_attempt_count": previous_attempts,
            "retry_generation": delivery.retry_generation,
        },
    )
    _append_alert_audit(
        session,
        tenant_id=tenant_id,
        alert_id=alert.id,
        actor=actor,
        action="delivery_retry_requested",
        details={"delivery_id": delivery.id, "channel": delivery.channel},
    )
    session.commit()

    from background_tasks import enqueue_notification_event
    from communications.events import build_notification_event

    context = {
        "id": alert.discrepancy_id,
        "deduplication_key": deduplication_key,
        "trans_id": alert.transaction_id,
        "severity": alert.severity,
        "status": alert.alert_type,
        "anomaly_type": alert.alert_type,
        "alert_type": alert.alert_type,
        "title": alert.title,
        "message": alert.message,
        "source": alert.source,
        "request_id": alert.request_id,
    }
    retry_event = build_notification_event(
        "reconciliation.exception_created",
        tenant_id=tenant_id,
        event_id=source_event_id,
        context=context,
        correlation_id=alert.transaction_id,
    )
    enqueue_result = enqueue_notification_event(retry_event)
    if enqueue_result.get("status") != "queued":
        delivery.status = "failed"
        delivery.error_code = "ALERT_RETRY_ENQUEUE_FAILED"
        delivery.last_error = str(enqueue_result.get("error") or "notification queue unavailable")[:1000]
        delivery.updated_at = datetime.now(timezone.utc)
        append_alert_event(
            session,
            alert,
            event_type="alert.delivery_retry_enqueue_failed",
            actor="system",
            details={"channel": delivery.channel, "delivery_id": delivery.id},
        )
        session.commit()
        return jsonify({
            "error": "retry_enqueue_failed",
            "message": "The alert retry could not be queued.",
            "delivery_id": delivery.id,
            "status": delivery.status,
        }), 503

    return jsonify({
        "alert_id": alert.id,
        "delivery_id": delivery.id,
        "channel": delivery.channel,
        "status": delivery.status,
        "attempt_count": int(delivery.attempt_count or 0),
        "retry_generation": int(delivery.retry_generation or 0),
        "job_id": enqueue_result.get("job_id"),
    }), 202


@bp.post("/alerts/<alert_id>/acknowledge")
@require_auth(required_permission="write:discrepancies")
@rate_limit(max_requests_per_minute=60)
def acknowledge_alert(alert_id: str):
    tenant_id, err = _resolve_tenant()
    if err:
        return err
    actor = _require_actor()

    alert = (
        session.query(Alert)
        .filter(Alert.tenant_id == tenant_id, Alert.id == alert_id)
        .one_or_none()
    )
    if alert is None:
        return jsonify({"error": "not_found", "message": "Alert not found."}), 404
    if alert.status == "resolved":
        return jsonify({"error": "conflict", "message": "Alert is already resolved."}), 409
    if alert.status in {"acknowledged", "suppressed"}:
        return jsonify({"error": "conflict", "message": f"Alert already in state '{alert.status}'."}), 409

    body = request.get_json(silent=True)
    if body is not None and not isinstance(body, dict):
        return jsonify({"error": "invalid_request", "message": "A JSON object is required."}), 400
    note = _clean_text(body.get("note") if isinstance(body, dict) else None)
    if note and len(note) > 4000:
        return jsonify({"error": "invalid_note", "message": "note must be at most 4000 characters."}), 400
    previous_status = alert.status
    alert.status = "acknowledged"
    alert.acknowledged_at = datetime.now(timezone.utc)
    alert.acknowledged_by = actor
    append_alert_event(
        session,
        alert,
        event_type="alert.acknowledged",
        actor=actor,
        from_status=previous_status,
        to_status="acknowledged",
        details={"note": note} if note else {},
    )
    _append_alert_audit(session, tenant_id=alert.tenant_id, alert_id=alert.id, actor=actor, action="acknowledged")
    session.flush()
    session.commit()
    return jsonify(_alert_to_dict(alert, include_deliveries=True)), 200


@bp.post("/alerts/<alert_id>/resolve")
@require_auth(required_permission="write:discrepancies")
@rate_limit(max_requests_per_minute=60)
def resolve_alert_endpoint(alert_id: str):
    tenant_id, err = _resolve_tenant()
    if err:
        return err
    actor = _require_actor()

    alert = (
        session.query(Alert)
        .filter(Alert.tenant_id == tenant_id, Alert.id == alert_id)
        .one_or_none()
    )
    if alert is None:
        return jsonify({"error": "not_found", "message": "Alert not found."}), 404
    if alert.status in {"resolved", "suppressed"}:
        return jsonify({"error": "conflict", "message": f"Alert is already {alert.status}."}), 409

    body = request.get_json(silent=True) or {}
    if not isinstance(body, dict):
        return jsonify({"error": "invalid_request", "message": "A JSON object is required."}), 400
    outcome = body.get("outcome")
    note = _clean_text(body.get("note") if body.get("note") else None)

    if outcome not in _ALLOWED_OUTCOMES:
        return jsonify({"error": "invalid_outcome", "message": f"outcome must be one of {_ALLOWED_OUTCOMES}"}), 400
    if note and len(note) > 4000:
        return jsonify({"error": "invalid_note", "message": "note must be at most 4000 characters."}), 400

    previous_status = alert.status
    alert.status = "resolved"
    alert.resolved_at = datetime.now(timezone.utc)
    alert.resolution_note = note
    alert.resolution_outcome = outcome
    append_alert_event(
        session,
        alert,
        event_type="alert.resolved",
        actor=actor,
        from_status=previous_status,
        to_status="resolved",
        details={"outcome": outcome, "note": note},
    )
    _append_alert_audit(
        session,
        tenant_id=alert.tenant_id,
        alert_id=alert.id,
        actor=actor,
        action="resolved",
        details={"outcome": outcome},
    )
    session.flush()
    session.commit()
    return jsonify(_alert_to_dict(alert, include_deliveries=True)), 200


@bp.post("/alerts/<alert_id>/suppress")
@require_auth(required_permission="write:discrepancies")
@rate_limit(max_requests_per_minute=60)
def suppress_alert(alert_id: str):
    tenant_id, err = _resolve_tenant()
    if err:
        return err
    actor = _require_actor()

    alert = (
        session.query(Alert)
        .filter(Alert.tenant_id == tenant_id, Alert.id == alert_id)
        .one_or_none()
    )
    if alert is None:
        return jsonify({"error": "not_found", "message": "Alert not found."}), 404
    if alert.status == "resolved":
        return jsonify({"error": "conflict", "message": "Alert is already resolved."}), 409
    if alert.status == "suppressed":
        return jsonify({"error": "conflict", "message": "Alert is already suppressed."}), 409

    note = _clean_text(request.get_json(silent=True).get("note") if isinstance(request.get_json(silent=True), dict) else None)
    previous_status = alert.status
    alert.status = "suppressed"
    alert.resolved_at = datetime.now(timezone.utc)
    alert.resolution_note = note
    append_alert_event(
        session,
        alert,
        event_type="alert.suppressed",
        actor=actor,
        from_status=previous_status,
        to_status="suppressed",
        details={"note": note} if note else {},
    )
    _append_alert_audit(session, tenant_id=alert.tenant_id, alert_id=alert.id, actor=actor, action="suppressed")
    session.flush()
    session.commit()
    return jsonify(_alert_to_dict(alert, include_deliveries=True)), 200


@bp.post("/alerts/<alert_id>/assign")
@require_auth(required_permission="write:discrepancies")
@rate_limit(max_requests_per_minute=60)
def assign_alert(alert_id: str):
    """Assign or unassign an open alert and append the operator action to history."""
    tenant_id, err = _resolve_tenant()
    if err:
        return err
    actor = _require_actor()
    body = request.get_json(silent=True)
    if not isinstance(body, dict):
        return jsonify({"error": "invalid_request", "message": "A JSON object is required."}), 400
    assignee = body.get("assigned_to")
    if assignee is not None and (not isinstance(assignee, str) or not assignee.strip() or len(assignee.strip()) > 128):
        return jsonify({"error": "invalid_assignee", "message": "assigned_to must be a non-empty identifier of at most 128 characters or null."}), 400
    alert = session.query(Alert).filter_by(tenant_id=tenant_id, id=alert_id).one_or_none()
    if alert is None:
        return jsonify({"error": "not_found", "message": "Alert not found."}), 404
    if alert.status in {"resolved", "suppressed"}:
        return jsonify({"error": "conflict", "message": f"Alert is already {alert.status}."}), 409
    old_assignee = alert.assigned_to
    alert.assigned_to = assignee.strip() if isinstance(assignee, str) else None
    alert.updated_at = datetime.now(timezone.utc)
    append_alert_event(
        session,
        alert,
        event_type="alert.assignment_changed",
        actor=actor,
        details={"from_assignee": old_assignee, "to_assignee": alert.assigned_to},
    )
    _append_alert_audit(
        session,
        tenant_id=tenant_id,
        alert_id=alert.id,
        actor=actor,
        action="assignment_changed",
        details={"assignee": alert.assigned_to},
    )
    session.commit()
    return jsonify(_alert_to_dict(alert, include_deliveries=True)), 200


@bp.post("/alerts/<alert_id>/investigate")
@require_auth(required_permission="write:discrepancies")
@rate_limit(max_requests_per_minute=60)
def add_investigation_note(alert_id: str):
    """Record investigation evidence and acknowledge the alert on first review."""
    tenant_id, err = _resolve_tenant()
    if err:
        return err
    actor = _require_actor()
    body = request.get_json(silent=True)
    if not isinstance(body, dict):
        return jsonify({"error": "invalid_request", "message": "A JSON object is required."}), 400
    note = _clean_text(body.get("note"))
    if not note or len(note) > 4000:
        return jsonify({"error": "invalid_note", "message": "note is required and must be at most 4000 characters."}), 400
    alert = session.query(Alert).filter_by(tenant_id=tenant_id, id=alert_id).one_or_none()
    if alert is None:
        return jsonify({"error": "not_found", "message": "Alert not found."}), 404
    if alert.status in {"resolved", "suppressed"}:
        return jsonify({"error": "conflict", "message": f"Alert is already {alert.status}."}), 409

    previous_status = alert.status
    if alert.status == "open":
        alert.status = "acknowledged"
        alert.acknowledged_at = datetime.now(timezone.utc)
        alert.acknowledged_by = actor
    prior_notes = alert.investigation_notes or ""
    alert.investigation_notes = f"{prior_notes}\n{note}".strip()[-20000:]
    alert.updated_at = datetime.now(timezone.utc)
    append_alert_event(
        session,
        alert,
        event_type="alert.investigation_note_added",
        actor=actor,
        from_status=previous_status,
        to_status=alert.status,
        details={"note": note},
    )
    _append_alert_audit(
        session,
        tenant_id=tenant_id,
        alert_id=alert.id,
        actor=actor,
        action="investigation_note_added",
        details={"status": alert.status},
    )
    session.commit()
    return jsonify(_alert_to_dict(alert, include_deliveries=True)), 200


@bp.get("/alerts/<alert_id>/escalation")
@require_auth(required_permission="read:discrepancies")
@rate_limit(max_requests_per_minute=60)
def get_alert_escalation(alert_id: str):
    tenant_id, err = _resolve_tenant()
    if err:
        return err

    alert = (
        session.query(Alert)
        .filter(Alert.tenant_id == tenant_id, Alert.id == alert_id)
        .one_or_none()
    )
    if alert is None:
        return jsonify({"error": "not_found", "message": "Alert not found."}), 404

    rules = session.query(EscalationRule).filter(
        EscalationRule.tenant_id == tenant_id,
        EscalationRule.active.is_(True),
    ).order_by(EscalationRule.priority.desc()).all()

    matching_rule = None
    for rule in rules:
        if (rule.condition_field == "severity"
                and rule.condition_operator == "equals"
                and str(rule.condition_value).lower() == str(alert.severity).lower()):
            matching_rule = rule
            break

    on_call = None
    now = datetime.now(timezone.utc)
    if alert.severity == "critical" or matching_rule is not None:
        active_rotation = (
            session.query(OnCallRotation)
            .filter(
                OnCallRotation.tenant_id == tenant_id,
                OnCallRotation.is_active.is_(True),
                OnCallRotation.shift_start <= now,
                OnCallRotation.shift_end > now,
            )
            .order_by(OnCallRotation.escalation_level.asc())
            .first()
        )
        if active_rotation is not None:
            on_call = {
                "id": active_rotation.id,
                "operator_id": active_rotation.operator_id,
                "operator_name": active_rotation.operator_name,
                "operator_email": active_rotation.operator_email,
                "shift_start": active_rotation.shift_start.isoformat(),
                "shift_end": active_rotation.shift_end.isoformat(),
                "escalation_level": active_rotation.escalation_level,
            }

    return jsonify(
        {
            "alert_id": alert.id,
            "current_severity": alert.severity,
            "assigned_to": alert.assigned_to,
            "escalation_rules": [
                {
                    "id": r.id,
                    "name": r.name,
                    "condition_field": r.condition_field,
                    "condition_operator": r.condition_operator,
                    "condition_value": r.condition_value,
                    "action": r.action,
                    "priority": r.priority,
                }
                for r in rules
            ],
            "action": f"rule:{matching_rule.id}:{matching_rule.action}" if matching_rule is not None else None,
            "on_call": on_call,
        }
    ), 200


@bp.post("/alerts/<alert_id>/escalate")
@require_auth(required_permission="write:discrepancies")
@rate_limit(max_requests_per_minute=60)
def escalate_alert_endpoint(alert_id: str):
    tenant_id, err = _resolve_tenant()
    if err:
        return err
    actor = _require_actor()

    alert = (
        session.query(Alert)
        .filter(Alert.tenant_id == tenant_id, Alert.id == alert_id)
        .one_or_none()
    )
    if alert is None:
        return jsonify({"error": "not_found", "message": "Alert not found."}), 404
    if alert.status in {"resolved", "suppressed"}:
        return jsonify({"error": "conflict", "message": f"Alert is already {alert.status}."}), 409

    body = request.get_json(silent=True) or {}
    if not isinstance(body, dict):
        return jsonify({"error": "invalid_request", "message": "A JSON object is required."}), 400
    rules = session.query(EscalationRule).filter(
        EscalationRule.tenant_id == tenant_id,
        EscalationRule.active.is_(True),
    ).order_by(EscalationRule.priority.desc()).all()
    requested_rule_id = body.get("rule_id")
    matching_rule = next((rule for rule in rules if rule.id == requested_rule_id), None) if requested_rule_id else None
    if requested_rule_id and matching_rule is None:
        return jsonify({"error": "invalid_rule", "message": "The escalation rule is not active for this tenant."}), 400
    if matching_rule is None:
        matching_rule = next((rule for rule in rules if (
            rule.condition_field == "severity"
            and rule.condition_operator == "equals"
            and str(rule.condition_value).lower() == alert.severity.lower()
            and rule.action in {"escalate", "notify"}
        )), None)
    if matching_rule is not None and matching_rule.action == "webhook":
        return jsonify({"error": "workflow_required", "message": "Webhook escalation must be executed by the configured escalation worker."}), 409

    now = datetime.now(timezone.utc)
    active_rotation = (
        session.query(OnCallRotation)
        .filter(
            OnCallRotation.tenant_id == tenant_id,
            OnCallRotation.is_active.is_(True),
            OnCallRotation.shift_start <= now,
            OnCallRotation.shift_end > now,
        )
        .order_by(OnCallRotation.escalation_level.asc())
        .first()
    )
    target = (matching_rule.target if matching_rule is not None else None) or (
        active_rotation.operator_id if active_rotation is not None else None
    )
    old_status = alert.status
    old_severity = alert.severity
    alert.severity = "critical"
    alert.status = "open"
    alert.acknowledged_at = None
    alert.acknowledged_by = None
    if target:
        alert.assigned_to = target[:128]
    alert.updated_at = datetime.now(timezone.utc)
    append_alert_event(
        session,
        alert,
        event_type="alert.escalated",
        actor=actor,
        from_status=old_status,
        to_status="open",
        details={
            "from_severity": old_severity,
            "to_severity": "critical",
            "rule_id": matching_rule.id if matching_rule is not None else None,
            "assigned_to": alert.assigned_to,
        },
    )
    _append_alert_audit(
        session,
        tenant_id=alert.tenant_id,
        alert_id=alert.id,
        actor=actor,
        action="escalated",
        details={"rule_id": matching_rule.id if matching_rule is not None else None, "assigned_to": alert.assigned_to},
    )
    if active_rotation is not None and active_rotation.operator_email:
        queue_alert_email_deliveries(
            session,
            alert=alert,
            recipients_override=[active_rotation.operator_email],
            idempotency_scope=f"escalation:{matching_rule.id if matching_rule is not None else 'manual'}",
        )
    session.flush()
    session.commit()
    return jsonify(_alert_to_dict(alert, include_deliveries=True)), 200
