"""
A16 — Email address change requested email.

Trigger: a change of account email was requested. Pending until confirmed.
Masked addresses where appropriate.
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

TEMPLATE_ID = "A16"
TRIGGER_EVENT = "auth.email_change_requested"

TEMPLATE = TemplateDefinition(
    template_id=TEMPLATE_ID,
    name="Email address change requested",
    category="authentication",
    version="1.0.0",
    trigger_event=TRIGGER_EVENT,
    recipient_type="account_owner",
    sender=SENDER_SECURITY,
    reply_to=SENDER_SUPPORT,
    subject_template="Confirm your new PesaGuard email address",
    preheader_template="Confirm change from {current_email} to {new_email}",
    required_variables=("current_email", "new_email", "confirmation_url", "expires_at"),
    optional_variables=("display_name",),
    cta={"label": "Confirm email change", "url_key": "confirmation_url"},
    sensitivity="confidential",
    preference_classification="essential",
    idempotency_strategy="single_use_token",
    icon_category="account_access",
)


def _render(context: dict[str, Any], urls: EmailUrls):
    current_email = context.get("current_email", "")
    new_email = context.get("new_email", "")
    display_name = context.get("display_name") or current_email
    confirmation_url = context.get("confirmation_url")
    expires_at = context.get("expires_at", "not specified")

    body = (
        paragraph(
            "Hello " + escape(str(display_name)) + ", you requested to change the "
            "email address on your PesaGuard account."
        )
        + '<table width="100%">'
        + detail_row("Current email", mask_email(current_email))
        + detail_row("New email", mask_email(new_email))
        + detail_row("Confirmation link expires", str(expires_at))
        + "</table>"
    )
    body += paragraph(
        "The change is not complete until you confirm the new address. If you did "
        "not request this change, you can ignore this message."
    )

    plain = (
        f"Hello {escape(str(display_name))}, you requested to change the email "
        f"address on your PesaGuard account.\n\n"
        f"Current email: {current_email}\n"
        f"New email: {new_email}\n"
        f"Confirmation link expires: {expires_at}\n"
    )
    if confirmation_url:
        plain += f"Confirm: {confirmation_url}\n"
    plain += (
        "The change is not complete until you confirm the new address. If you did "
        "not request this change, you can ignore this message."
    )

    html, plain_text = render_shell(
        preheader=f"Confirm change from {current_email} to {new_email}",
        subject="Confirm your new PesaGuard email address",
        category="Account access",
        headline="Confirm your new email address",
        body_html=body,
        icon=icon_for("account_access"),
        cta_label="Confirm email change",
        cta_url=confirmation_url,
        notice_html=(
            "This confirmation link is single-use and time-limited. The change is "
            "pending until you confirm."
        ),
        notice_kind="info",
        plain_text=plain,
    )
    return (
        html,
        plain_text,
        "Confirm email change",
        confirmation_url,
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
