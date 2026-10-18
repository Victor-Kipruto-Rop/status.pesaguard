"""
A06 — Suspicious login detected email.

Trigger: unusual account activity detected. Distinguishes suspicion from
confirmed compromise; does not disclose detection rules.
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

TEMPLATE_ID = "A06"
TRIGGER_EVENT = "security.suspicious_login_detected"

TEMPLATE = TemplateDefinition(
    template_id=TEMPLATE_ID,
    name="Suspicious login detected",
    category="authentication",
    version="1.0.0",
    trigger_event=TRIGGER_EVENT,
    recipient_type="account_owner",
    sender=SENDER_SECURITY,
    reply_to=SENDER_SUPPORT,
    subject_template="Unusual sign-in detected on your PesaGuard account",
    preheader_template="We detected a sign-in we did not recognise",
    required_variables=("event_time", "secure_account_url"),
    optional_variables=("account_email", "device_info", "location", "display_name"),
    cta={"label": "Secure your account", "url_key": "secure_account_url"},
    sensitivity="confidential",
    preference_classification="essential",
    idempotency_strategy="event_idempotency_key",
    icon_category="security_alert",
)


def _render(context: dict[str, Any], urls: EmailUrls):
    account_email = context.get("account_email", "")
    display_name = context.get("display_name") or account_email
    event_time = context.get("event_time", "not specified")
    device_info = context.get("device_info")
    location = context.get("location")
    secure_url = context.get("secure_account_url") or urls.security_settings()

    body = (
        paragraph(
            "Hello " + escape(str(display_name)) + ", we detected a sign-in to "
            "your PesaGuard account that we could not confirm was you."
        )
        + '<table width="100%">'
        + detail_row("Email address", mask_email(account_email))
        + detail_row("Detected at", str(event_time))
    )
    if device_info:
        body += detail_row("Device context", escape(str(device_info)))
    if location:
        body += detail_row("Location context", escape(str(location)))
    body += "</table>"
    body += paragraph(
        "This is a suspicion, not a confirmed compromise. We recommend you secure "
        "your account by reviewing active sessions and changing your password if "
        "you do not recognise this sign-in."
    )

    plain = (
        f"Hello {escape(str(display_name))}, we detected a sign-in to your "
        f"PesaGuard account that we could not confirm was you.\n\n"
        f"Email address: {account_email}\n"
        f"Detected at: {event_time}\n"
    )
    if device_info:
        plain += f"Device context: {device_info}\n"
    if location:
        plain += f"Location context: {location}\n"
    plain += (
        f"Secure your account: {secure_url}\n\n"
        "This is a suspicion, not a confirmed compromise."
    )

    html, plain_text = render_shell(
        preheader="We detected a sign-in we did not recognise",
        subject="Unusual sign-in detected on your PesaGuard account",
        category="Security alert",
        headline="Unusual sign-in detected",
        body_html=body,
        icon=icon_for("security_alert"),
        cta_label="Secure your account",
        cta_url=secure_url,
        notice_html=(
            "PesaGuard will never ask you to send your password or a verification "
            "code by email."
        ),
        notice_kind="error",
        plain_text=plain,
    )
    return (
        html,
        plain_text,
        "Secure your account",
        secure_url,
        None,
        "error",
        None,
    )


REGISTRY.register(TEMPLATE)


def register_renderer() -> None:
    from ...dispatcher import _RENDERERS

    _RENDERERS[TEMPLATE_ID] = _render


register_renderer()

__all__ = ["TEMPLATE", "TEMPLATE_ID", "TRIGGER_EVENT", "_render"]
