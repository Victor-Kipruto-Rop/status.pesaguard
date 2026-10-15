from __future__ import annotations

import hashlib
import json
import uuid
from datetime import datetime, timezone
from typing import Any
from zoneinfo import ZoneInfoNotFoundError

from sqlalchemy.orm import Session

from data_protection import decrypt_value, encrypt_value
from ..core.interfaces import CommunicationProvider, NotificationRequest
from ..core.exceptions import CommunicationError
from ..core.enums import CommunicationChannel, CommunicationPriority, NotificationStatus
from ..models import CommunicationAttempt, CommunicationNotification
from ..models import CommunicationConsent, CommunicationPreference
from ..domain import is_allowed_now, render_template
from ..models import CommunicationTemplate
from ..outbox import enqueue_notification
from ..core.state_machine import transition


class NotificationService:
    """Persist and submit one notification through an injected provider."""

    def __init__(self, session: Session, provider: CommunicationProvider, *, protect_recipient: bool = False):
        self.session = session
        self.provider = provider
        self.protect_recipient = protect_recipient

    def _prepare_request(self, request: NotificationRequest) -> NotificationRequest:
        if not isinstance(request.tenant_id, str) or not request.tenant_id.strip():
            raise ValueError("tenant_id is required")
        if not isinstance(request.channel, CommunicationChannel):
            raise ValueError("unsupported communication channel")
        if not isinstance(request.priority, CommunicationPriority):
            raise ValueError("unsupported communication priority")
        if not isinstance(request.idempotency_key, str) or not request.idempotency_key.strip():
            raise ValueError("idempotency_key is required for notification delivery")
        if not isinstance(request.message, str) or not request.message.strip():
            raise ValueError("message is required")
        if not isinstance(request.variables, dict) and not hasattr(request.variables, "items"):
            raise ValueError("variables must be an object")

        recipient = self.provider.validate_recipient(request.channel, request.recipient)
        message = request.message
        if request.template_id:
            template = self.session.query(CommunicationTemplate).filter_by(
                id=request.template_id,
                tenant_id=request.tenant_id,
            ).one_or_none()
            if template is None:
                raise ValueError("template not found")
            if template.status != "approved":
                raise ValueError("template is not approved")
            if template.channel != request.channel.value:
                raise ValueError("template channel does not match notification channel")
            message = render_template(template, dict(request.variables))
            if not message.strip():
                raise ValueError("rendered message is empty")

        return NotificationRequest(
            tenant_id=request.tenant_id.strip(),
            recipient=recipient,
            message=message,
            channel=request.channel,
            priority=request.priority,
            notification_id=request.notification_id,
            idempotency_key=request.idempotency_key.strip(),
            template_id=request.template_id,
            variables=request.variables,
            correlation_id=request.correlation_id,
            trace_id=request.trace_id,
        )

    @staticmethod
    def _request_fingerprint(request: NotificationRequest) -> str:
        payload = {
            "channel": request.channel.value,
            "recipient": request.recipient,
            "message": request.message,
            "priority": request.priority.value,
            "template_id": request.template_id,
            "variables": dict(request.variables),
        }
        encoded = json.dumps(payload, sort_keys=True, separators=(",", ":"), default=str)
        return hashlib.sha256(encoded.encode("utf-8")).hexdigest()

    def _existing(self, request: NotificationRequest) -> CommunicationNotification | None:
        existing = self.session.query(CommunicationNotification).filter_by(
            tenant_id=request.tenant_id,
            idempotency_key=request.idempotency_key,
        ).one_or_none()
        if existing is None:
            return None
        expected = self._request_fingerprint(request)
        metadata = dict(existing.metadata_json or {})
        stored = metadata.get("request_fingerprint")
        if stored is None:
            legacy_request = NotificationRequest(
                tenant_id=existing.tenant_id,
                recipient=self._plain_recipient(existing.recipient),
                message=existing.message,
                channel=CommunicationChannel(existing.channel),
                priority=CommunicationPriority(existing.priority),
                idempotency_key=existing.idempotency_key,
                template_id=existing.template_id,
                variables={
                    key: value
                    for key, value in metadata.items()
                    if key not in {"email_cost", "email_latency_ms", "provider_response", "last_provider_error"}
                },
            )
            stored = self._request_fingerprint(legacy_request)
        if stored != expected:
            raise ValueError("idempotency_key was already used for a different notification")
        return existing

    def _plain_recipient(self, recipient: str) -> str:
        if not self.protect_recipient:
            return recipient
        value = decrypt_value(recipient)
        if not isinstance(value, str):
            raise ValueError("stored notification recipient is invalid")
        return value

    def _stored_recipient(self, recipient: str) -> str:
        return encrypt_value(recipient) if self.protect_recipient else recipient

    def _check_preferences(self, request: NotificationRequest) -> None:
        preference = self.session.query(CommunicationPreference).filter_by(
            tenant_id=request.tenant_id,
            recipient=request.recipient,
        ).one_or_none()
        if preference is not None:
            selected_channels = preference.channels or []
            if not isinstance(selected_channels, list):
                raise ValueError("recipient channel preference is invalid")
            if selected_channels and request.channel.value not in selected_channels:
                raise ValueError("recipient has disabled this communication channel")
            try:
                quiet_hours_allow = is_allowed_now(preference)
            except (ValueError, ZoneInfoNotFoundError) as exc:
                raise ValueError("recipient preference timezone is invalid") from exc
            if request.priority.value not in {"critical", "high"} and not quiet_hours_allow:
                raise ValueError("recipient is currently in quiet hours")

        purpose = str(request.variables.get("purpose", "transactional"))
        if purpose == "marketing":
            if preference is not None and not bool(preference.marketing_opt_in):
                raise ValueError("marketing preference is disabled for this recipient")
            consent = self.session.query(CommunicationConsent).filter_by(
                tenant_id=request.tenant_id,
                recipient=request.recipient,
                channel=request.channel.value,
                purpose=purpose,
                granted=1,
            ).one_or_none()
            if consent is None:
                raise ValueError("marketing consent is required for this recipient")

    def enqueue(self, request: NotificationRequest, *, cost: dict[str, Any] | None = None, latency_ms: int | None = None) -> CommunicationNotification:
        """Persist a notification and outbox entry without contacting a provider."""
        request = self._prepare_request(request)
        self._check_preferences(request)
        existing = self._existing(request)
        if existing is not None:
            return existing
        metadata = dict(request.variables)
        metadata["request_fingerprint"] = self._request_fingerprint(request)
        if cost is not None:
            metadata.setdefault("email_cost", cost)
        if latency_ms is not None:
            metadata.setdefault("email_latency_ms", latency_ms)
        notification = CommunicationNotification(
            id=request.notification_id or f"notification_{uuid.uuid4().hex}",
            tenant_id=request.tenant_id,
            channel=request.channel.value,
            recipient=self._stored_recipient(request.recipient),
            message=request.message,
            template_id=request.template_id,
            priority=request.priority.value,
            status=NotificationStatus.QUEUED.value,
            idempotency_key=request.idempotency_key,
            provider=self.provider.name,
            correlation_id=request.correlation_id,
            trace_id=request.trace_id,
            metadata_json=metadata,
        )
        self.session.add(notification)
        self.session.flush()
        notification.status = transition(notification.status, NotificationStatus.QUEUED)
        enqueue_notification(self.session, notification)
        return notification

    def send(self, request: NotificationRequest, *, cost: dict[str, Any] | None = None, latency_ms: int | None = None) -> CommunicationNotification:
        request = self._prepare_request(request)
        self._check_preferences(request)
        existing = self._existing(request)
        if existing is not None:
            return existing

        started_at = datetime.now(timezone.utc)
        metadata = dict(request.variables)
        metadata["request_fingerprint"] = self._request_fingerprint(request)
        if cost is not None:
            metadata.setdefault("email_cost", cost)
        if latency_ms is not None:
            metadata.setdefault("email_latency_ms", latency_ms)

        notification = CommunicationNotification(
            id=request.notification_id or f"notification_{uuid.uuid4().hex}",
            tenant_id=request.tenant_id,
            channel=request.channel.value,
            recipient=self._stored_recipient(request.recipient),
            message=request.message,
            template_id=request.template_id,
            priority=request.priority.value,
            status=NotificationStatus.CREATED.value,
            idempotency_key=request.idempotency_key,
            provider=self.provider.name,
            correlation_id=request.correlation_id,
            trace_id=request.trace_id,
            metadata_json=metadata,
        )
        self.session.add(notification)
        self.session.flush()
        notification.status = transition(notification.status, NotificationStatus.QUEUED)
        notification.status = transition(notification.status, NotificationStatus.PROCESSING)
        attempt = CommunicationAttempt(
            id=f"attempt_{uuid.uuid4().hex}",
            notification_id=notification.id,
            tenant_id=notification.tenant_id,
            attempt_number=1,
            provider=self.provider.name,
            status=NotificationStatus.PROCESSING.value,
            started_at=started_at,
            cost=cost or None,
            latency_ms=latency_ms,
        )
        self.session.add(attempt)
        try:
            result = self.provider.send(request)
            elapsed_ms = int((datetime.now(timezone.utc) - started_at).total_seconds() * 1000)
            final_latency_ms = latency_ms if latency_ms is not None else elapsed_ms
            notification.status = transition(notification.status, result.status)
            notification.provider_message_id = result.provider_message_id
            attempt.status = result.status
            attempt.provider_message_id = result.provider_message_id
            attempt.latency_ms = final_latency_ms
            attempt.cost = cost or attempt.cost
            attempt.completed_at = datetime.now(timezone.utc)
            notification.metadata_json = {
                **dict(notification.metadata_json or {}),
                "provider_response": dict(result.raw_response),
                "email_cost": cost,
                "email_latency_ms": final_latency_ms,
            }
        except CommunicationError as exc:
            elapsed_ms = int((datetime.now(timezone.utc) - started_at).total_seconds() * 1000)
            final_latency_ms = latency_ms if latency_ms is not None else elapsed_ms
            target_status = NotificationStatus.RETRYING if exc.retryable else NotificationStatus.FAILED
            notification.status = transition(notification.status, target_status)
            notification.failure_code = exc.code
            notification.failure_reason = str(exc)
            attempt.status = notification.status
            attempt.error_code = exc.code
            attempt.error_detail = str(exc)
            attempt.latency_ms = final_latency_ms
            attempt.cost = cost or attempt.cost
            attempt.completed_at = datetime.now(timezone.utc)
            notification.metadata_json = {
                **dict(notification.metadata_json or {}),
                "email_cost": cost,
                "email_latency_ms": final_latency_ms,
                "last_provider_error": exc.code,
            }
        self.session.flush()
        return notification
