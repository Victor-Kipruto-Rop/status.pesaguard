"""
A10 — Password change blocked email.

Trigger: a password change was not completed. Generic explanation, no internal
diagnostics.
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

TEMPLATE_ID = "A10"
TRIGGER_EVENT = "auth.password_change_blocked"

TEMPLATE = TemplateDefinition(
    template_id=TEMPLATE_ID,
    name="Password change blocked",
    category="authentication",
    version="1.0.0",
    trigger_event=TRIGGER_EVENT,
    recipient_type="account_owner",
    sender=SENDER_SECURITY,
    reply_to=SENDER_SUPPORT,
    subject_template="PesaGuard password change was not completed",
    preheader_template="Your password change could not be processed",
    required_variables=("account_email", "next_step_url"),
    optional_variables=("display_name", "attempted_at"),
    cta={"label": "Review account security", "url_key": "next_step_url"},
    sensitivity="internal",
    preference_classification="essential",
    idempotency_strategy="event_idempotency_key",
    icon_category="password_recovery",
)


def _render(context: dict[str, Any], urls: EmailUrls):
    account_email = context.get("account_email", "")
    display_name = context.get("display_name") or account_email
    attempted_at = context.get("attempted_at", "not specified")
    next_step_url = context.get("next_step_url") or urls.security_settings()

    body = (
        paragraph(
            "Hello " + escape(str(display_name)) + ", a password change for your "
            "PesaGuard account was not completed."
        )
        + '<table width="100%">'
        + detail_row("Email address", mask_email(account_email))
        + detail_row("Attempted at", str(attempted_at))
        + "</table>"
    )
    body += paragraph(
        "Your password remains unchanged. If you did not attempt this change, "
        "secure your account and contact PesaGuard support."
    )

    plain = (
        f"Hello {escape(str(display_name))}, a password change for your PesaGuard "
        f"account was not completed.\n\n"
        f"Email address: {account_email}\n"
        f"Attempted at: {attempted_at}\n"
        f"Review security: {next_step_url}\n\n"
        "Your password remains unchanged."
    )

    html, plain_text = render_shell(
        preheader="Your password change could not be processed",
        subject="PesaGuard password change was not completed",
        category="Password recovery",
        headline="Password change not completed",
        body_html=body,
        icon=icon_for("password_recovery"),
        cta_label="Review account security",
        cta_url=next_step_url,
        notice_html=(
            "No internal diagnostics are shared. Contact support if you did not "
            "attempt this change."
        ),
        notice_kind="warn",
        plain_text=plain,
    )
    return (
        html,
        plain_text,
        "Review account security",
        next_step_url,
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
