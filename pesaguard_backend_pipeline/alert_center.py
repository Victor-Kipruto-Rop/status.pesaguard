"""Durable, tenant-scoped alert records and lifecycle history."""

from __future__ import annotations

import hashlib
import os
import uuid
from datetime import datetime, timezone
from email.utils import parseaddr
from typing import Any, Mapping

from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from models import Alert, AlertDelivery, AlertEvent


def _text(value: Any, *, limit: int, default: str = "") -> str:
    if value is None:
        return default
    if not isinstance(value, str):
        value = str(value)
    return value.strip()[:limit]


def _safe_event_details(payload: Mapping[str, Any]) -> dict[str, Any]:
    details: dict[str, Any] = {}
    for key in ("anomaly_type", "status", "trans_id", "transaction_id", "source", "correlation_id", "event"):
        value = payload.get(key)
        if isinstance(value, (str, int, float, bool)):
            details[key] = str(value)[:255]
    anomalies = payload.get("anomalies")
    if isinstance(anomalies, (list, tuple)):
        details["anomalies"] = [str(value)[:128] for value in anomalies[:20]]
    source_event_id = payload.get("event_id")
    if isinstance(source_event_id, str) and source_event_id.strip():
        details["source_event_id"] = source_event_id.strip()[:255]
    deduplication_key = (
        payload.get("deduplication_key") or payload.get("fingerprint") or payload.get("id")
        or payload.get("trans_id") or payload.get("transaction_id") or payload.get("event")
    )
    if isinstance(deduplication_key, (str, int)):
        details["deduplication_key"] = str(deduplication_key)[:255]
    return details


def _fingerprint(tenant_id: str, payload: Mapping[str, Any]) -> str:
    """Build a stable alert identity so retries and repeated source events coalesce."""
    category = _text(
        payload.get("alert_type") or payload.get("category") or payload.get("anomaly_type") or payload.get("status"),
        limit=64,
        default="system",
    ).lower()
    source = _text(payload.get("source"), limit=64, default="reconciliation").lower()
    identity = _text(
        payload.get("deduplication_key") or payload.get("fingerprint") or payload.get("id")
        or payload.get("trans_id") or payload.get("transaction_id") or payload.get("event"),
        limit=255,
        default="unkeyed-event",
    )
    material = "\x1f".join((tenant_id, source, category, identity))
    return hashlib.sha256(material.encode("utf-8")).hexdigest()


def _append_event(
    session: Session,
    *,
    tenant_id: str,
    alert_id: str,
    event_key: str,
    event_type: str,
    actor: str,
    from_status: str | None = None,
    to_status: str | None = None,
    details: Mapping[str, Any] | None = None,
) -> AlertEvent:
    event = AlertEvent(
        id=f"ale_{uuid.uuid4().hex}",
        tenant_id=tenant_id,
        alert_id=alert_id,
        event_key=event_key[:255],
        event_type=event_type[:64],
        actor=actor[:128],
        from_status=from_status,
        to_status=to_status,
        details=dict(details or {}),
    )
    session.add(event)
    session.flush()
    return event


def append_alert_event(
    session: Session,
    alert: Alert,
    *,
    event_type: str,
    actor: str,
    from_status: str | None = None,
    to_status: str | None = None,
    details: Mapping[str, Any] | None = None,
) -> AlertEvent:
    """Append a lifecycle event using a fresh key for each operator action."""
    return _append_event(
        session,
        tenant_id=alert.tenant_id,
        alert_id=alert.id,
        event_key=f"operator:{uuid.uuid4().hex}",
        event_type=event_type,
        actor=actor,
        from_status=from_status,
        to_status=to_status,
        details=details,
    )


def record_alert(
    session: Session,
    *,
    tenant_id: str,
    payload: Mapping[str, Any],
    actor: str = "system",
) -> tuple[Alert, bool, bool]:
    """Persist an alert or a duplicate event and return (alert, created, replay)."""
    tenant_id = _text(tenant_id, limit=128)
    if not tenant_id:
        raise ValueError("tenant_id is required")
    if not isinstance(payload, Mapping):
        raise ValueError("alert payload must be an object")

    fingerprint = _fingerprint(tenant_id, payload)
    now = datetime.now(timezone.utc)
    event_id = _text(payload.get("event_id"), limit=255)
    event_key = f"source:{hashlib.sha256(event_id.encode('utf-8')).hexdigest()}" if event_id else f"delivery:{uuid.uuid4().hex}"
    alert_type = _text(
        payload.get("alert_type") or payload.get("category") or payload.get("anomaly_type") or payload.get("status"),
        limit=64,
        default="system",
    )
    severity = _text(payload.get("severity"), limit=16, default="warning").lower()
    if severity not in {"info", "warning", "critical"}:
        severity = "warning"
    transaction_id = _text(payload.get("transaction_id") or payload.get("trans_id"), limit=255) or None
    request_id = _text(payload.get("request_id") or payload.get("correlation_id"), limit=128) or None
    title = _text(payload.get("title"), limit=255, default=f"{severity.title()} {alert_type.replace('_', ' ')} alert")
    message = _text(payload.get("message") or payload.get("details"), limit=4000) or None
    source = _text(payload.get("source"), limit=64, default="reconciliation")
    alert_id = f"alert_{uuid.uuid4().hex}"

    alert = Alert(
        id=alert_id,
        tenant_id=tenant_id,
        alert_type=alert_type,
        severity=severity,
        status="open",
        title=title,
        message=message,
        source=source,
        transaction_id=transaction_id,
        discrepancy_id=_text(payload.get("discrepancy_id") or payload.get("id"), limit=128) or None,
        fingerprint=fingerprint,
        duplicate_count=0,
        last_seen_at=now,
        request_id=request_id,
        created_at=now,
        updated_at=now,
    )

    try:
        with session.begin_nested():
            session.add(alert)
            session.flush()
        created = True
    except IntegrityError:
        created = False
        alert = session.query(Alert).filter_by(tenant_id=tenant_id, fingerprint=fingerprint).with_for_update().one_or_none()
        if alert is None:
            raise

    if created:
        _append_event(
            session,
            tenant_id=tenant_id,
            alert_id=alert.id,
            event_key=event_key,
            event_type="alert.created",
            actor=actor,
            to_status="open",
            details=_safe_event_details(payload),
        )
        session.add(AlertDelivery(
            id=f"ald_{uuid.uuid4().hex}",
            tenant_id=tenant_id,
            alert_id=alert.id,
            channel="in_app",
            idempotency_key=f"{alert.id}:in_app",
            status="available",
        ))
        session.flush()
        return alert, True, False

    previous_event = session.query(AlertEvent).filter_by(tenant_id=tenant_id, event_key=event_key).one_or_none()
    if previous_event is not None:
        if previous_event.alert_id != alert.id:
            raise ValueError("event_id was already used for a different alert")
        return alert, False, True

    old_status = alert.status
    alert.duplicate_count = int(alert.duplicate_count or 0) + 1
    alert.last_seen_at = now
    alert.updated_at = now
    _append_event(
        session,
        tenant_id=tenant_id,
        alert_id=alert.id,
        event_key=event_key,
        event_type="alert.duplicate_suppressed",
        actor=actor,
        from_status=old_status,
        to_status=old_status,
        details=_safe_event_details(payload),
    )
    session.flush()
    return alert, False, False


def delivery_for(
    session: Session,
    *,
    alert: Alert,
    channel: str,
    idempotency_key: str,
    destination: str | None = None,
    status: str = "queued",
) -> AlertDelivery:
    """Create or return a durable delivery record for one alert destination."""
    existing = session.query(AlertDelivery).filter_by(
        tenant_id=alert.tenant_id,
        idempotency_key=idempotency_key,
    ).one_or_none()
    if existing is not None:
        return existing
    destination_hash = hashlib.sha256(destination.strip().casefold().encode("utf-8")).hexdigest() if destination else None
    row = AlertDelivery(
        id=f"ald_{uuid.uuid4().hex}",
        tenant_id=alert.tenant_id,
        alert_id=alert.id,
        channel=channel,
        idempotency_key=idempotency_key[:255],
        destination_hash=destination_hash,
        status=status,
    )
    session.add(row)
    session.flush()
    return row


def configured_alert_email_recipients(tenant_settings: Mapping[str, Any] | None = None) -> list[str]:
    """Return validated tenant-specific recipients or the configured operations list."""
    settings = tenant_settings or {}
    configured = settings.get("alert_email_recipients", settings.get("email_alert_recipients"))
    if configured is None:
        configured = os.getenv("ALERT_EMAIL_RECIPIENTS", "")
    values = configured if isinstance(configured, (list, tuple, set)) else str(configured).split(",")
    recipients: list[str] = []
    for value in values:
        recipient = _text(value, limit=255)
        _, parsed = parseaddr(recipient)
        if "\r" in recipient or "\n" in recipient or parsed != recipient or "@" not in recipient:
            continue
        if not any(existing.casefold() == recipient.casefold() for existing in recipients):
            recipients.append(recipient)
    return recipients


def queue_alert_email_deliveries(
    session: Session,
    *,
    alert: Alert,
    tenant_settings: Mapping[str, Any] | None = None,
    recipients_override: list[str] | None = None,
    idempotency_scope: str | None = None,
) -> list[AlertDelivery]:
    """Write configured alert emails into the durable communications outbox."""
    recipients = (
        configured_alert_email_recipients({"alert_email_recipients": recipients_override})
        if recipients_override is not None
        else configured_alert_email_recipients(tenant_settings)
    )
    if not recipients:
        return []

    from communications.application.email_service import EmailService
    from communications.core.enums import CommunicationChannel, CommunicationPriority
    from communications.core.interfaces import NotificationRequest
    from communications.providers.email import EmailProviderConfig

    priority = {
        "critical": CommunicationPriority.CRITICAL,
        "warning": CommunicationPriority.HIGH,
        "info": CommunicationPriority.NORMAL,
    }.get(alert.severity, CommunicationPriority.NORMAL)
    subject = f"PesaGuard {alert.severity.upper()} alert: {alert.title}"
    body = alert.message or alert.title
    email_service = None
    provider_error: Exception | None = None
    try:
        config = EmailProviderConfig.from_environment()
        configured_from = os.getenv("ALERT_EMAIL_FROM", "").strip()
        if configured_from:
            config.from_email = configured_from
        email_service = EmailService(session, config=config)
    except (RuntimeError, ValueError) as exc:
        provider_error = exc

    rows: list[AlertDelivery] = []
    for recipient in recipients:
        destination_hash = hashlib.sha256(recipient.casefold().encode("utf-8")).hexdigest()
        row = delivery_for(
            session,
            alert=alert,
            channel="email",
            idempotency_key=f"{alert.id}:{idempotency_scope or 'initial'}:email:{destination_hash}",
            destination=recipient,
            status="queued" if email_service else "not_configured",
        )
        rows.append(row)
        if row.communication_notification_id or row.status == "dead_letter":
            continue
        if row.status == "not_configured" and provider_error is not None:
            continue
        if row.status in {"failed", "not_configured"}:
            row.status = "queued"
            row.error_code = None
            row.last_error = None
            row.updated_at = datetime.now(timezone.utc)
        if provider_error is not None:
            row.error_code = "EMAIL_PROVIDER_NOT_CONFIGURED"
            row.last_error = str(provider_error)[:1000]
            row.updated_at = datetime.now(timezone.utc)
            append_alert_event(
                session,
                alert,
                event_type="alert.delivery_not_configured",
                actor="system",
                details={"channel": "email", "destination_hash": destination_hash},
            )
            continue

        request = NotificationRequest(
            tenant_id=alert.tenant_id,
            recipient=recipient,
            message=body,
            channel=CommunicationChannel.EMAIL,
            priority=priority,
            notification_id=f"notification_{uuid.uuid4().hex}",
            idempotency_key=f"alert:{alert.id}:{idempotency_scope or 'initial'}:email:{destination_hash}",
            variables={
                "purpose": "transactional",
                "subject": subject,
                "text": body,
                "alert_id": alert.id,
                "alert_delivery_id": row.id,
            },
            correlation_id=alert.request_id,
        )
        try:
            notification = email_service.enqueue(request)
            row.communication_notification_id = notification.id
            row.status = notification.status
            row.updated_at = datetime.now(timezone.utc)
            append_alert_event(
                session,
                alert,
                event_type="alert.delivery_queued",
                actor="system",
                details={"channel": "email", "destination_hash": destination_hash},
            )
        except Exception as exc:
            row.status = "failed"
            row.error_code = getattr(exc, "code", "EMAIL_ENQUEUE_FAILED")[:64]
            row.last_error = str(exc)[:1000]
            row.updated_at = datetime.now(timezone.utc)
            append_alert_event(
                session,
                alert,
                event_type="alert.delivery_enqueue_failed",
                actor="system",
                details={"channel": "email", "destination_hash": destination_hash},
            )
    session.flush()
    return rows
