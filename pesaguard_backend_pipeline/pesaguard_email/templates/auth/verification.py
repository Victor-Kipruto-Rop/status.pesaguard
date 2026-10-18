"""
A02 — Email verification email.

Trigger: account creation succeeds / verification requested.
Token-based, time-limited, single-use, invalidated after successful verification.
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

TEMPLATE_ID = "A02"
TRIGGER_EVENT = "user.email_verification_requested"

TEMPLATE = TemplateDefinition(
    template_id=TEMPLATE_ID,
    name="Email verification",
    category="authentication",
    version="1.0.0",
    trigger_event=TRIGGER_EVENT,
    recipient_type="account_owner",
    sender="verify@pesaguard.co.ke",
    reply_to=SENDER_SUPPORT,
    subject_template="Verify your PesaGuard email address",
    preheader_template="Verify {account_email} to activate your account",
    required_variables=("account_email", "verification_url", "expires_at"),
    optional_variables=("display_name",),
    cta={"label": "Verify email address", "url_key": "verification_url"},
    sensitivity="confidential",
    preference_classification="essential",
    idempotency_strategy="single_use_token",
    icon_category="email_verification",
)


def _render(context: dict[str, Any], urls: EmailUrls):
    account_email = context.get("account_email", "")
    display_name = context.get("display_name") or account_email
    verification_url = context.get("verification_url") or urls.verify_email(
        str(context.get("verification_token", ""))
    )
    expires_at = context.get("expires_at", "not specified")

    body = (
        paragraph(
            "Hello " + escape(str(display_name)) + ", please verify your email "
            "address to activate your PesaGuard account."
        )
        + '<table width="100%">'
        + detail_row("Email address", mask_email(account_email))
        + detail_row("Verification link expires", str(expires_at))
        + "</table>"
    )
    body += paragraph(
        "This link is single-use and expires after the time shown. If you did not "
        "create this account, you can ignore this message — no account was "
        "created in your name."
    )

    plain = (
        f"Hello {escape(str(display_name))}, please verify your email address to "
        f"activate your PesaGuard account.\n\n"
        f"Email address: {account_email}\n"
        f"Verification link expires: {expires_at}\n"
        f"Verify: {verification_url}\n\n"
        "This link is single-use and expires after the time shown. If you did not "
        "create this account, you can ignore this message."
    )

    html, plain_text = render_shell(
        preheader=f"Verify {account_email} to activate your account",
        subject="Verify your PesaGuard email address",
        category="Email verification",
        headline="Verify your email address",
        body_html=body,
        icon=icon_for("email_verification"),
        cta_label="Verify email address",
        cta_url=verification_url,
        notice_html=(
            "This verification link is single-use and time-limited. It cannot be "
            "reused after verification."
        ),
        notice_kind="info",
        plain_text=plain,
    )
    return html, plain_text, "Verify email address", verification_url, None, "info", None


REGISTRY.register(TEMPLATE)


def register_renderer() -> None:
    from ...dispatcher import _RENDERERS

    _RENDERERS[TEMPLATE_ID] = _render


register_renderer()

__all__ = ["TEMPLATE", "TEMPLATE_ID", "TRIGGER_EVENT", "_render"]
