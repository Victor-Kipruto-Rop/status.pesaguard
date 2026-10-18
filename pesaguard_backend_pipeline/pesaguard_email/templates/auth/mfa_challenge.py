"""
A13 — MFA challenge or recovery event email.

Trigger: an MFA challenge or recovery event occurred. No MFA secrets or recovery
codes are included. Recovery guidance only if the recovery flow exists.
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

TEMPLATE_ID = "A13"
TRIGGER_EVENT = "auth.mfa_challenge"

TEMPLATE = TemplateDefinition(
    template_id=TEMPLATE_ID,
    name="MFA challenge or recovery event",
    category="authentication",
    version="1.0.0",
    trigger_event=TRIGGER_EVENT,
    recipient_type="account_owner",
    sender=SENDER_SECURITY,
    reply_to=SENDER_SUPPORT,
    subject_template="MFA challenge on your PesaGuard account",
    preheader_template="An MFA challenge was initiated at {challenge_time}",
    required_variables=("account_email", "challenge_time"),
    optional_variables=("display_name", "challenge_type", "recovery_url", "security_url"),
    cta={"label": "Review security settings", "url_key": "security_url"},
    sensitivity="confidential",
    preference_classification="essential",
    idempotency_strategy="event_idempotency_key",
    icon_category="security_alert",
)


def _render(context: dict[str, Any], urls: EmailUrls):
    account_email = context.get("account_email", "")
    display_name = context.get("display_name") or account_email
    challenge_time = context.get("challenge_time", "not specified")
    challenge_type = context.get("challenge_type", "verification")
    recovery_url = context.get("recovery_url")
    security_url = context.get("security_url") or urls.security_settings()

    body = (
        paragraph(
            "Hello " + escape(str(display_name)) + ", an MFA challenge was "
            "initiated on your PesaGuard account."
        )
        + '<table width="100%">'
        + detail_row("Email address", mask_email(account_email))
        + detail_row("Challenge time", str(challenge_time))
        + detail_row("Challenge type", escape(str(challenge_type)))
        + "</table>"
    )
    body += paragraph(
        "If you initiated this challenge, complete it through the PesaGuard "
        "dashboard. If you did not, your credentials may be compromised — change "
        "your password immediately."
    )

    plain = (
        f"Hello {escape(str(display_name))}, an MFA challenge was initiated on "
        f"your PesaGuard account.\n\n"
        f"Email address: {account_email}\n"
        f"Challenge time: {challenge_time}\n"
        f"Challenge type: {challenge_type}\n"
        f"Review security: {security_url}\n\n"
        "If you initiated this challenge, complete it through the PesaGuard "
        "dashboard. If you did not, change your password immediately."
    )

    html, plain_text = render_shell(
        preheader=f"An MFA challenge was initiated at {challenge_time}",
        subject="MFA challenge on your PesaGuard account",
        category="Security alert",
        headline="MFA challenge initiated",
        body_html=body,
        icon=icon_for("security_alert"),
        cta_label="Review security settings",
        cta_url=security_url,
        notice_html=(
            "No MFA secrets or recovery codes are included in this message."
        ),
        notice_kind="warn",
        plain_text=plain,
    )
    return (
        html,
        plain_text,
        "Review security settings",
        security_url,
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
