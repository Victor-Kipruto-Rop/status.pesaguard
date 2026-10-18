"""
A03 — Email verification reminder.

Trigger: verification still pending after a documented reminder cadence.
Avoids repeated or excessive sends.
"""

from __future__ import annotations

from html import escape
from typing import Any

from ..base import detail_row, mask_email, paragraph
from ...registry import REGISTRY, TemplateDefinition
from ...shell import render_shell
from ...urls import EmailUrls
from ...icons import icon_for
from ...brand import SENDER_SUPPORT

TEMPLATE_ID = "A03"
TRIGGER_EVENT = "user.email_verification_reminder"

TEMPLATE = TemplateDefinition(
    template_id=TEMPLATE_ID,
    name="Verification reminder",
    category="authentication",
    version="1.0.0",
    trigger_event=TRIGGER_EVENT,
    recipient_type="account_owner",
    sender="verify@pesaguard.co.ke",
    reply_to=SENDER_SUPPORT,
    subject_template="Reminder: verify your PesaGuard email address",
    preheader_template="Your email address {account_email} is still unverified",
    required_variables=("account_email", "verification_url", "expires_at"),
    optional_variables=("display_name", "request_another_url"),
    cta={"label": "Verify email address", "url_key": "verification_url"},
    secondary_cta={"label": "Request another link", "url_key": "request_another_url"},
    sensitivity="confidential",
    preference_classification="essential",
    idempotency_strategy="event_idempotency_key",
    icon_category="email_verification",
)


def _render(context: dict[str, Any], urls: EmailUrls):
    account_email = context.get("account_email", "")
    display_name = context.get("display_name") or account_email
    verification_url = context.get("verification_url") or urls.verify_email(
        str(context.get("verification_token", ""))
    )
    expires_at = context.get("expires_at", "not specified")
    request_another_url = context.get("request_another_url") or urls.verify_email_resend()

    body = (
        paragraph(
            "Hello " + escape(str(display_name)) + ", your PesaGuard email address "
            "is still unverified. Verifying your email unlocks your account."
        )
        + '<table width="100%">'
        + detail_row("Email address", mask_email(account_email))
        + detail_row("Link expires", str(expires_at))
        + "</table>"
    )
    body += paragraph(
        "If you did not request this reminder, no action is needed. Your account "
        "remains unverified until you confirm the address."
    )

    plain = (
        f"Hello {escape(str(display_name))}, your PesaGuard email address is still "
        f"unverified. Verifying your email unlocks your account.\n\n"
        f"Email address: {account_email}\n"
        f"Link expires: {expires_at}\n"
        f"Verify: {verification_url}\n"
        f"Request another link: {request_another_url}\n\n"
        "If you did not request this reminder, no action is needed."
    )

    html, plain_text = render_shell(
        preheader=f"Your email address {account_email} is still unverified",
        subject="Reminder: verify your PesaGuard email address",
        category="Email verification",
        headline="Verify your email address",
        body_html=body,
        icon=icon_for("email_verification"),
        cta_label="Verify email address",
        cta_url=verification_url,
        secondary_html=(
            "Didn't receive the link? "
            '<a href="' + escape(request_another_url) + '">Request another link</a>.'
        ),
        notice_html=(
            "Reminder emails are sent on a limited cadence. If you keep having "
            "trouble, contact PesaGuard support."
        ),
        notice_kind="info",
        plain_text=plain,
    )
    return (
        html,
        plain_text,
        "Verify email address",
        verification_url,
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
