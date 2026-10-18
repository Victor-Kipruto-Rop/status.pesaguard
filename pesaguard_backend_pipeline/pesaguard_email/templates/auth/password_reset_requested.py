"""
A07 — Password reset requested email.

Trigger: password reset requested. Generic responses prevent email enumeration.
Token-based, time-limited, single-use. Never sends the password itself.
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

TEMPLATE_ID = "A07"
TRIGGER_EVENT = "auth.password_reset_requested"

TEMPLATE = TemplateDefinition(
    template_id=TEMPLATE_ID,
    name="Password reset requested",
    category="authentication",
    version="1.0.0",
    trigger_event=TRIGGER_EVENT,
    recipient_type="account_owner",
    sender=SENDER_SECURITY,
    reply_to=SENDER_SUPPORT,
    subject_template="Reset your PesaGuard password",
    preheader_template="A password reset was requested for {account_email}",
    required_variables=("account_email", "reset_url", "expires_at"),
    optional_variables=("display_name",),
    cta={"label": "Reset password", "url_key": "reset_url"},
    sensitivity="confidential",
    preference_classification="essential",
    idempotency_strategy="single_use_token",
    icon_category="password_recovery",
)


def _render(context: dict[str, Any], urls: EmailUrls):
    account_email = context.get("account_email", "")
    display_name = context.get("display_name") or account_email
    reset_url = context.get("reset_url") or urls.reset_password(
        str(context.get("reset_token", ""))
    )
    expires_at = context.get("expires_at", "not specified")

    body = (
        paragraph(
            "Hello " + escape(str(display_name)) + ", a password reset was "
            "requested for your PesaGuard account."
        )
        + '<table width="100%">'
        + detail_row("Email address", mask_email(account_email))
        + detail_row("Reset link expires", str(expires_at))
        + "</table>"
    )
    body += paragraph(
        "If you did not request this reset, you can ignore this message. Your "
        "password was not changed. PesaGuard will never ask you to send your "
        "password by email."
    )

    plain = (
        f"Hello {escape(str(display_name))}, a password reset was requested for "
        f"your PesaGuard account.\n\n"
        f"Email address: {account_email}\n"
        f"Reset link expires: {expires_at}\n"
        f"Reset: {reset_url}\n\n"
        "If you did not request this reset, you can ignore this message. Your "
        "password was not changed."
    )

    html, plain_text = render_shell(
        preheader=f"A password reset was requested for {account_email}",
        subject="Reset your PesaGuard password",
        category="Password recovery",
        headline="Reset your password",
        body_html=body,
        icon=icon_for("password_recovery"),
        cta_label="Reset password",
        cta_url=reset_url,
        notice_html=(
            "This reset link is single-use and time-limited. If you did not "
            "request it, no action is needed."
        ),
        notice_kind="warn",
        plain_text=plain,
    )
    return (
        html,
        plain_text,
        "Reset password",
        reset_url,
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
