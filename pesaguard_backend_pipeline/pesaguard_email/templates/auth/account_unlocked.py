"""
A19 — Account unlocked email.

Trigger: account access was restored. Confirmation with security follow-up.
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

TEMPLATE_ID = "A19"
TRIGGER_EVENT = "auth.account_unlocked"

TEMPLATE = TemplateDefinition(
    template_id=TEMPLATE_ID,
    name="Account unlocked",
    category="authentication",
    version="1.0.0",
    trigger_event=TRIGGER_EVENT,
    recipient_type="account_owner",
    sender=SENDER_SECURITY,
    reply_to=SENDER_SUPPORT,
    subject_template="Your PesaGuard account has been unlocked",
    preheader_template="Account access was restored at {unlocked_at}",
    required_variables=("account_email", "unlocked_at"),
    optional_variables=("display_name", "security_url", "login_url"),
    cta={"label": "Sign in to dashboard", "url_key": "login_url"},
    sensitivity="internal",
    preference_classification="essential",
    idempotency_strategy="event_idempotency_key",
    icon_category="recovery",
)


def _render(context: dict[str, Any], urls: EmailUrls):
    account_email = context.get("account_email", "")
    display_name = context.get("display_name") or account_email
    unlocked_at = context.get("unlocked_at", "not specified")
    security_url = context.get("security_url") or urls.security_settings()
    login_url = context.get("login_url") or urls.dashboard_home()

    body = (
        paragraph(
            "Hello " + escape(str(display_name)) + ", your PesaGuard account has "
            "been unlocked and access has been restored."
        )
        + '<table width="100%">'
        + detail_row("Email address", mask_email(account_email))
        + detail_row("Unlocked at", str(unlocked_at))
        + "</table>"
    )
    body += paragraph(
        "We recommend reviewing your recent sessions and security settings to make "
        "sure everything looks correct."
    )

    plain = (
        f"Hello {escape(str(display_name))}, your PesaGuard account has been "
        f"unlocked and access has been restored.\n\n"
        f"Email address: {account_email}\n"
        f"Unlocked at: {unlocked_at}\n"
        f"Sign in: {login_url}\n"
        f"Review security: {security_url}\n\n"
        "We recommend reviewing your recent sessions and security settings."
    )

    html, plain_text = render_shell(
        preheader=f"Account access was restored at {unlocked_at}",
        subject="Your PesaGuard account has been unlocked",
        category="Recovery",
        headline="Your account has been unlocked",
        body_html=body,
        icon=icon_for("recovery"),
        cta_label="Sign in to dashboard",
        cta_url=login_url,
        notice_html=(
            "Review your recent sessions and security settings to make sure "
            "everything looks correct."
        ),
        notice_kind="info",
        plain_text=plain,
    )
    return (
        html,
        plain_text,
        "Sign in to dashboard",
        login_url,
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
