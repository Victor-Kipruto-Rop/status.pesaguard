"""
A20 — Account recovery requested email.

Trigger: recovery request acknowledged. Expected next step and security guidance.
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

TEMPLATE_ID = "A20"
TRIGGER_EVENT = "auth.account_recovery_requested"

TEMPLATE = TemplateDefinition(
    template_id=TEMPLATE_ID,
    name="Account recovery requested",
    category="authentication",
    version="1.0.0",
    trigger_event=TRIGGER_EVENT,
    recipient_type="account_owner",
    sender=SENDER_SECURITY,
    reply_to=SENDER_SUPPORT,
    subject_template="PesaGuard account recovery request received",
    preheader_template="We received your account recovery request",
    required_variables=("account_email", "requested_at"),
    optional_variables=("display_name", "expected_timeframe", "report_url"),
    cta={"label": "Report unauthorized request", "url_key": "report_url"},
    sensitivity="confidential",
    preference_classification="essential",
    idempotency_strategy="event_idempotency_key",
    icon_category="account_access",
)


def _render(context: dict[str, Any], urls: EmailUrls):
    account_email = context.get("account_email", "")
    display_name = context.get("display_name") or account_email
    requested_at = context.get("requested_at", "not specified")
    expected_timeframe = context.get("expected_timeframe")
    report_url = context.get("report_url") or urls.support()

    body = (
        paragraph(
            "Hello " + escape(str(display_name)) + ", we received an account "
            "recovery request for your PesaGuard account."
        )
        + '<table width="100%">'
        + detail_row("Email address", mask_email(account_email))
        + detail_row("Requested at", str(requested_at))
    )
    if expected_timeframe:
        body += detail_row("Expected timeframe", str(expected_timeframe))
    body += "</table>"
    body += paragraph(
        "If you did not request this recovery, report it immediately using the "
        "link below. No account changes will be made without identity "
        "verification."
    )

    plain = (
        f"Hello {escape(str(display_name))}, we received an account recovery "
        f"request for your PesaGuard account.\n\n"
        f"Email address: {account_email}\n"
        f"Requested at: {requested_at}\n"
    )
    if expected_timeframe:
        plain += f"Expected timeframe: {expected_timeframe}\n"
    plain += f"Report unauthorized request: {report_url}\n\n"
    plain += (
        "If you did not request this recovery, report it immediately."
    )

    html, plain_text = render_shell(
        preheader="We received your account recovery request",
        subject="PesaGuard account recovery request received",
        category="Security alert",
        headline="Account recovery request received",
        body_html=body,
        icon=icon_for("security_alert"),
        cta_label="Report unauthorized request",
        cta_url=report_url,
        notice_html=(
            "No account changes will be made without identity verification."
        ),
        notice_kind="warn",
        plain_text=plain,
    )
    return (
        html,
        plain_text,
        "Report unauthorized request",
        report_url,
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
