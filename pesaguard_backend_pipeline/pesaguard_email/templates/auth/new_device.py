"""
A14 — New device or session registered email.

Trigger: a new device or session was registered. Generalised location only when
reliable; never presented as certain.
"""

from __future__ import annotations

from html import escape
from typing import Any

from ..base import detail_row, mask_email, paragraph
from ...registry import REGISTRY, TemplateDefinition
from ...shell import render_shell
from ...urls import EmailUrls
from ...icons import icon_for
from ...brand import SENDER_SECURITY, SENDER_SUPPORT

TEMPLATE_ID = "A14"
TRIGGER_EVENT = "auth.new_device_registered"

TEMPLATE = TemplateDefinition(
    template_id=TEMPLATE_ID,
    name="New device or session registered",
    category="authentication",
    version="1.0.0",
    trigger_event=TRIGGER_EVENT,
    recipient_type="account_owner",
    sender=SENDER_SECURITY,
    reply_to=SENDER_SUPPORT,
    subject_template="New device signed in to your PesaGuard account",
    preheader_template="A new session was registered at {session_time}",
    required_variables=("account_email", "session_time"),
    optional_variables=("display_name", "device_info", "location", "security_url"),
    cta={"label": "Review active sessions", "url_key": "security_url"},
    sensitivity="internal",
    preference_classification="essential",
    idempotency_strategy="event_idempotency_key",
    icon_category="account_access",
)


def _render(context: dict[str, Any], urls: EmailUrls):
    account_email = context.get("account_email", "")
    display_name = context.get("display_name") or account_email
    session_time = context.get("session_time", "not specified")
    device_info = context.get("device_info")
    location = context.get("location")
    security_url = context.get("security_url") or urls.security_settings()

    body = (
        paragraph(
            "Hello " + escape(str(display_name)) + ", a new device or session was "
            "registered on your PesaGuard account."
        )
        + '<table width="100%">'
        + detail_row("Email address", mask_email(account_email))
        + detail_row("Registered at", str(session_time))
    )
    if device_info:
        body += detail_row("Device", escape(str(device_info)))
    if location:
        body += detail_row("Approximate location", escape(str(location)))
    body += "</table>"
    body += paragraph(
        "If you recognise this device, no action is needed. If you do not "
        "recognise it, sign out of all sessions and change your password."
    )

    plain = (
        f"Hello {escape(str(display_name))}, a new device or session was "
        f"registered on your PesaGuard account.\n\n"
        f"Email address: {account_email}\n"
        f"Registered at: {session_time}\n"
    )
    if device_info:
        plain += f"Device: {device_info}\n"
    if location:
        plain += f"Approximate location: {location}\n"
    plain += (
        f"Review active sessions: {security_url}\n\n"
        "If you do not recognise this device, sign out of all sessions and change "
        "your password."
    )

    html, plain_text = render_shell(
        preheader=f"A new session was registered at {session_time}",
        subject="New device signed in to your PesaGuard account",
        category="Account access",
        headline="New device registered",
        body_html=body,
        icon=icon_for("account_access"),
        cta_label="Review active sessions",
        cta_url=security_url,
        notice_html=(
            "Device and location information is approximate and should not be "
            "treated as certain."
        ),
        notice_kind="info",
        plain_text=plain,
    )
    return (
        html,
        plain_text,
        "Review active sessions",
        security_url,
        None,
        "info",
        None,
    )


REGISTRY.register(TEMPLATE)


def register_renderer() -> None:
    from ...dispatcher import _RENDERERS

    _RENDERERS[TEMPLATE_ID] = _render


register_renderer()

__all__ = ["TEMPLATE", "TEMPLATE_ID", "TRIGGER_EVENT", "_render"]
