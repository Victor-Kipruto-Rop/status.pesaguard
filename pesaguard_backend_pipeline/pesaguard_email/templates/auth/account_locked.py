"""
A18 — Account locked email.

Trigger: account was locked. Clear explanation, recovery route, no internal risk
details.
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

TEMPLATE_ID = "A18"
TRIGGER_EVENT = "auth.account_locked"

TEMPLATE = TemplateDefinition(
    template_id=TEMPLATE_ID,
    name="Account locked",
    category="authentication",
    version="1.0.0",
    trigger_event=TRIGGER_EVENT,
    recipient_type="account_owner",
    sender=SENDER_SECURITY,
    reply_to=SENDER_SUPPORT,
    subject_template="Your PesaGuard account has been locked",
    preheader_template="Your account was locked at {locked_at}",
    required_variables=("account_email", "locked_at"),
    optional_variables=(
        "display_name",
        "is_temporary",
        "unlock_at",
        "recovery_url",
    ),
    cta={"label": "Request account review", "url_key": "recovery_url"},
    sensitivity="confidential",
    preference_classification="essential",
    idempotency_strategy="event_idempotency_key",
    icon_category="security_alert",
)


def _render(context: dict[str, Any], urls: EmailUrls):
    account_email = context.get("account_email", "")
    display_name = context.get("display_name") or account_email
    locked_at = context.get("locked_at", "not specified")
    is_temporary = bool(context.get("is_temporary", True))
    unlock_at = context.get("unlock_at")
    recovery_url = context.get("recovery_url") or urls.support()

    body = (
        paragraph(
            "Hello " + escape(str(display_name)) + ", your PesaGuard account has "
            "been locked."
        )
        + '<table width="100%">'
        + detail_row("Email address", mask_email(account_email))
        + detail_row("Locked at", str(locked_at))
        + detail_row("Lock type", "Temporary" if is_temporary else "Permanent")
    )
    if unlock_at:
        body += detail_row("Unlocks at", str(unlock_at))
    body += "</table>"
    body += paragraph(
        "If you believe this lock was made in error, you can request an account "
        "review. PesaGuard support will verify your identity before making any "
        "changes."
    )

    plain = (
        f"Hello {escape(str(display_name))}, your PesaGuard account has been "
        f"locked.\n\n"
        f"Email address: {account_email}\n"
        f"Locked at: {locked_at}\n"
        f"Lock type: {'Temporary' if is_temporary else 'Permanent'}\n"
    )
    if unlock_at:
        plain += f"Unlocks at: {unlock_at}\n"
    plain += f"Request account review: {recovery_url}\n\n"
    plain += (
        "If you believe this lock was made in error, you can request an account "
        "review."
    )

    html, plain_text = render_shell(
        preheader=f"Your account was locked at {locked_at}",
        subject="Your PesaGuard account has been locked",
        category="Security alert",
        headline="Your account has been locked",
        body_html=body,
        icon=icon_for("security_alert"),
        cta_label="Request account review",
        cta_url=recovery_url,
        notice_html=(
            "PesaGuard support will verify your identity before making any "
            "changes to your account."
        ),
        notice_kind="error",
        plain_text=plain,
    )
    return (
        html,
        plain_text,
        "Request account review",
        recovery_url,
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
