"""
A01 — Account welcome email.

Trigger: account creation succeeds.
Does not imply business verification, tenant provisioning, M-Pesa integration,
or dashboard access is complete.
"""

from __future__ import annotations

from html import escape
from typing import Any

from ..base import detail_row, format_datetime, mask_email, paragraph
from ...registry import REGISTRY, TemplateDefinition
from ...shell import render_shell
from ...urls import EmailUrls
from ...icons import icon_for
from ...brand import SENDER_NO_REPLY, SENDER_SUPPORT

TEMPLATE_ID = "A01"
TRIGGER_EVENT = "account.created"

TEMPLATE = TemplateDefinition(
    template_id=TEMPLATE_ID,
    name="Account welcome",
    category="authentication",
    version="1.0.0",
    trigger_event=TRIGGER_EVENT,
    recipient_type="account_owner",
    sender=SENDER_NO_REPLY,
    reply_to=SENDER_SUPPORT,
    subject_template="Welcome to PesaGuard",
    preheader_template="Your PesaGuard account has been created",
    required_variables=("account_email", "account_state", "created_at", "dashboard_url"),
    optional_variables=("display_name", "next_step"),
    cta={"label": "Continue to dashboard", "url_key": "dashboard_url"},
    sensitivity="internal",
    preference_classification="essential",
    idempotency_strategy="event_idempotency_key",
    icon_category="authentication",
)


def _render(context: dict[str, Any], urls: EmailUrls):
    display_name = context.get("display_name") or context.get("account_email")
    account_email = context.get("account_email", "")
    account_state = context.get("account_state", "created")
    created_at = format_datetime(str(context.get("created_at", "")))
    next_step = context.get(
        "next_step",
        "Verify your email address to begin using PesaGuard.",
    )
    dashboard_url = context.get("dashboard_url") or urls.dashboard_home()

    body = (
        paragraph("Welcome to PesaGuard, " + escape(str(display_name)) + ".")
        + paragraph(
            "Your account has been created. You can now sign in and explore the "
            "dashboard. Business verification, tenant provisioning, and M-Pesa "
            "integration are separate steps and are not yet active."
        )
        + '<table width="100%">'
        + detail_row("Account email", mask_email(account_email))
        + detail_row("Account state", str(account_state).replace("_", " ").title())
        + detail_row("Created at", created_at)
        + detail_row("Next step", next_step)
        + "</table>"
    )
    body += paragraph(
        "Protect your login credentials. PesaGuard will never ask you to send "
        "your password by email."
    )

    plain = (
        f"Welcome to PesaGuard, {escape(str(display_name))}.\n\n"
        "Your account has been created. You can now sign in and explore the "
        "dashboard. Business verification, tenant provisioning, and M-Pesa "
        "integration are separate steps and are not yet active.\n\n"
        f"Account email: {account_email}\n"
        f"Account state: {account_state}\n"
        f"Created at: {created_at}\n"
        f"Next step: {next_step}\n"
        f"Dashboard: {dashboard_url}\n\n"
        "Protect your login credentials. PesaGuard will never ask you to send "
        "your password by email."
    )

    html, plain_text = render_shell(
        preheader="Your PesaGuard account has been created",
        subject="Welcome to PesaGuard",
        category="Account",
        headline="Welcome to PesaGuard",
        body_html=body,
        icon=icon_for("authentication"),
        cta_label="Continue to dashboard",
        cta_url=dashboard_url,
        plain_text=plain,
    )
    return html, plain_text, "Continue to dashboard", dashboard_url, None, "info", None


REGISTRY.register(TEMPLATE)


def register_renderer() -> None:
    from ...dispatcher import _RENDERERS

    _RENDERERS[TEMPLATE_ID] = _render


register_renderer()

__all__ = ["TEMPLATE", "TEMPLATE_ID", "TRIGGER_EVENT", "_render"]
