"""
Event-to-template dispatcher for PesaGuard transactional email.

Connects verified application events to registered email templates, enforces
notification preferences, tenant isolation, and idempotency, and delegates
delivery to the existing communications NotificationService / outbox.
"""

from __future__ import annotations

import hashlib
import logging
from typing import Any, Callable, Mapping

from sqlalchemy.orm import Session

from communications.application.notification_service import NotificationService
from communications.core.enums import CommunicationChannel, CommunicationPriority
from communications.core.interfaces import NotificationRequest
from communications.domain import is_allowed_now
from communications.models import CommunicationPreference
from communications.providers.email import EmailProviderConfig, EmailProviderFactory
from otel_tracing import trace_span
from .registry import REGISTRY, RenderedTemplate, TemplateDefinition
from .shell import render_shell
from .urls import EmailUrls, urls_from_env

logger = logging.getLogger("pesaguard.email")


class DispatchResult:
    __slots__ = ("template_id", "notification_id", "status", "skipped", "reason")

    def __init__(
        self,
        *,
        template_id: str,
        notification_id: str | None,
        status: str,
        skipped: bool = False,
        reason: str | None = None,
    ) -> None:
        self.template_id = template_id
        self.notification_id = notification_id
        self.status = status
        self.skipped = skipped
        self.reason = reason


_RENDERERS: dict[str, Callable] = {}


def _render_template(
    definition: TemplateDefinition,
    context: Mapping[str, Any],
    urls: EmailUrls,
) -> RenderedTemplate:
    # Reject missing required variables before rendering so critical information
    # is never silently replaced with invented values.
    missing = [
        name for name in definition.required_variables if name not in context
    ]
    if missing:
        raise ValueError(
            "missing required template variables: " + ", ".join(sorted(missing))
        )
    # Import locally so template modules (which register renderers) are loaded.
    from . import templates  # noqa: F401

    renderer = _RENDERERS.get(definition.template_id)
    if renderer is None:
        raise ValueError(f"no renderer for template: {definition.template_id}")

    (
        html_body,
        plain_text,
        cta_label,
        cta_url,
        notice,
        notice_kind,
        support_html,
    ) = renderer(dict(context), urls)

    subject = _render_text(definition.subject_template, context)
    preheader = _render_text(definition.preheader_template, context)

    html, plain = render_shell(
        preheader=preheader,
        subject=subject,
        category=definition.icon().label,
        headline=context.get("headline", subject),
        body_html=html_body,
        icon=definition.icon(),
        cta_label=cta_label,
        cta_url=cta_url,
        notice_html=notice,
        notice_kind=notice_kind,
        support_html=support_html,
        plain_text=plain_text,
    )

    return RenderedTemplate(
        template_id=definition.template_id,
        subject=subject,
        preheader=preheader,
        html=html,
        plain_text=plain or "",
        sender=definition.sender,
        reply_to=definition.reply_to,
        icon=definition.icon(),
    )


def _render_text(template: str, context: Mapping[str, Any]) -> str:
    try:
        return template.format(**dict(context))
    except KeyError as exc:
        raise ValueError(f"missing template variable: {exc}") from exc


def _idempotency_key(
    template_id: str, tenant_id: str, recipient: str, context: Mapping[str, Any]
) -> str:
    event_id = str(context.get("event_id") or "")
    correlation_id = str(context.get("correlation_id") or "")
    payload = f"{template_id}|{tenant_id}|{recipient}|{event_id}|{correlation_id}"
    return "email_" + hashlib.sha256(payload.encode("utf-8")).hexdigest()[:64]


def _preference_allows(
    session: Session,
    *,
    tenant_id: str,
    recipient: str,
    classification: str,
) -> bool:
    if classification == "essential":
        return True
    preference = (
        session.query(CommunicationPreference)
        .filter_by(tenant_id=tenant_id, recipient=recipient)
        .one_or_none()
    )
    if preference is None:
        return True
    try:
        return is_allowed_now(preference)
    except Exception:
        return True


def send_templated_email(
    session: Session,
    *,
    template_id: str,
    tenant_id: str,
    recipient: str,
    context: Mapping[str, Any],
    urls: EmailUrls | None = None,
    priority: CommunicationPriority = CommunicationPriority.NORMAL,
    correlation_id: str | None = None,
    trace_id: str | None = None,
) -> DispatchResult:
    """Render and enqueue a templated email through the existing outbox.

    Honours notification preferences and tenant isolation. Returns a
    DispatchResult describing whether the email was queued or skipped.
    """
    with trace_span(
        "email.send_templated_email",
        trace_id=trace_id,
        template_id=template_id,
        tenant_id=tenant_id,
        recipient=recipient,
    ):
        urls = urls or urls_from_env()
        definition = REGISTRY.get(template_id)

        if not tenant_id or not tenant_id.strip():
            return DispatchResult(
                template_id=template_id,
                notification_id=None,
                status="rejected",
                skipped=True,
                reason="tenant_id is required",
            )
        if not recipient or not recipient.strip():
            return DispatchResult(
                template_id=template_id,
                notification_id=None,
                status="rejected",
                skipped=True,
                reason="recipient is required",
            )

        if not _preference_allows(
            session,
            tenant_id=tenant_id,
            recipient=recipient,
            classification=definition.preference_classification,
        ):
            return DispatchResult(
                template_id=template_id,
                notification_id=None,
                status="suppressed",
                skipped=True,
                reason="suppressed by recipient preferences",
            )

        rendered = _render_template(definition, context, urls)
        idempotency_key = _idempotency_key(template_id, tenant_id, recipient, context)

        variables: dict[str, Any] = {
            "subject": rendered.subject,
            "text": rendered.plain_text,
            "html": rendered.html,
            "preheader": rendered.preheader,
            "reply_to": rendered.reply_to,
        }
        variables.update(dict(context))

        notification = NotificationService(
            session,
            EmailProviderFactory(config=EmailProviderConfig()).create(),
        ).enqueue(
            NotificationRequest(
                tenant_id=tenant_id.strip(),
                recipient=recipient.strip(),
                channel=CommunicationChannel.EMAIL,
                priority=priority,
                idempotency_key=idempotency_key,
                template_id=template_id,
                variables=variables,
                correlation_id=correlation_id,
                trace_id=trace_id,
            )
        )
        return DispatchResult(
            template_id=template_id,
            notification_id=notification.id,
            status=notification.status,
        )


__all__ = ["send_templated_email", "DispatchResult", "_RENDERERS"]