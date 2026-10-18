"""
A05 — Login notification email.

Trigger: successful login. Informational only; device/location context is
generalised and never presented as certain.
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

TEMPLATE_ID = "A05"
TRIGGER_EVENT = "auth.login_success"

TEMPLATE = TemplateDefinition(
    template_id=TEMPLATE_ID,
    name="Login notification",
    category="authentication",
    version="1.0.0",
    trigger_event=TRIGGER_EVENT,
    recipient_type="account_owner",
    sender=SENDER_SECURITY,
    reply_to=SENDER_SUPPORT,
    subject_template="New sign-in to your PesaGuard account",
    preheader_template="Signed in at {login_time}",
    required_variables=("login_time", "login_url"),
    optional_variables=("account_email", "device_info", "location", "display_name"),
    cta={"label": "Review account security", "url_key": "login_url"},
    sensitivity="internal",
    preference_classification="essential",
    idempotency_strategy="event_idempotency_key",
    icon_category="account_access",
)


def _render(context: dict[str, Any], urls: EmailUrls):
    account_email = context.get("account_email", "")
    display_name = context.get("display_name") or account_email
    login_time = context.get("login_time", "not specified")
    device_info = context.get("device_info")
    location = context.get("location")
    login_url = context.get("login_url") or urls.security_settings()

    body = (
        paragraph(
            "Hello " + escape(str(display_name)) + ", you signed in to your "
            "PesaGuard account."
        )
        + '<table width="100%">'
        + detail_row("Email address", mask_email(account_email))
        + detail_row("Sign-in time", str(login_time))
    )
    if device_info:
        body += detail_row("Device", escape(str(device_info)))
    if location:
        body += detail_row("Approximate location", escape(str(location)))
    body += "</table>"
    body += paragraph(
        "This is an informational notification. If you recognise this sign-in, no "
        "action is needed. Device and location information is approximate and "
        "should not be treated as certain."
    )

    plain = (
        f"Hello {escape(str(display_name))}, you signed in to your PesaGuard "
        f"account.\n\n"
        f"Email address: {account_email}\n"
        f"Sign-in time: {login_time}\n"
    )
    if device_info:
        plain += f"Device: {device_info}\n"
    if location:
        plain += f"Approximate location: {location}\n"
    plain += (
        f"Review security: {login_url}\n\n"
        "This is an informational notification. If you recognise this sign-in, no "
        "action is needed."
    )

    html, plain_text = render_shell(
        preheader=f"Signed in at {login_time}",
        subject="New sign-in to your PesaGuard account",
        category="Account access",
        headline="New sign-in to your account",
        body_html=body,
        icon=icon_for("account_access"),
        cta_label="Review account security",
        cta_url=login_url,
        notice_html=(
            "If this was not you, change your password and review your sessions "
            "immediately."
        ),
        notice_kind="warn",
        plain_text=plain,
    )
    return (
        html,
        plain_text,
        "Review account security",
        login_url,
        None,
        "warn",
        None,
    )


REGISTRY.register(TEMPLATE)


def register_renderer() -> None:
    from ...dispatcher import _RENDERERS

    _RENDERERS[TEMPLATE_ID] = _render


register_renderer()

__all__ = ["TEMPLATE", "TEMPLATE_ID", "TRIGGER_EVENT", "_render"]
