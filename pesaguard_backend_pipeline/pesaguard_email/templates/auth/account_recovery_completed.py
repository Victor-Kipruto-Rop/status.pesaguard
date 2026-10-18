"""
A21 — Account recovery completed or denied email.

Trigger: recovery completed or denied. Accurate outcome, no sensitive evidence.
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

TEMPLATE_ID = "A21"
TRIGGER_EVENT = "auth.account_recovery_completed"

TEMPLATE = TemplateDefinition(
    template_id=TEMPLATE_ID,
    name="Account recovery completed or denied",
    category="authentication",
    version="1.0.0",
    trigger_event=TRIGGER_EVENT,
    recipient_type="account_owner",
    sender=SENDER_SECURITY,
    reply_to=SENDER_SUPPORT,
    subject_template="PesaGuard account recovery {outcome}",
    preheader_template="Your account recovery request has been {outcome}",
    required_variables=("account_email", "outcome", "resolved_at"),
    optional_variables=("display_name", "next_step", "support_url"),
    cta={"label": "Contact support", "url_key": "support_url"},
    sensitivity="confidential",
    preference_classification="essential",
    idempotency_strategy="event_idempotency_key",
    icon_category="recovery",
)


def _render(context: dict[str, Any], urls: EmailUrls):
    account_email = context.get("account_email", "")
    display_name = context.get("display_name") or account_email
    outcome = context.get("outcome", "processed")
    resolved_at = context.get("resolved_at", "not specified")
    next_step = context.get("next_step")
    support_url = context.get("support_url") or urls.support()

    body = (
        paragraph(
            "Hello " + escape(str(display_name)) + ", your PesaGuard account "
            "recovery request has been " + str(outcome) + "."
        )
        + '<table width="100%">'
        + detail_row("Email address", mask_email(account_email))
        + detail_row("Outcome", str(outcome).title())
        + detail_row("Resolved at", str(resolved_at))
        + "</table>"
    )
    if next_step:
        body += detail_row("Next step", str(next_step))
    body += paragraph(
        "If you did not request this recovery or believe the outcome is incorrect, "
        "contact PesaGuard support."
    )

    plain = (
        f"Hello {escape(str(display_name))}, your PesaGuard account recovery "
        f"request has been {outcome}.\n\n"
        f"Email address: {account_email}\n"
        f"Outcome: {outcome}\n"
        f"Resolved at: {resolved_at}\n"
    )
    if next_step:
        plain += f"Next step: {next_step}\n"
    plain += f"Contact support: {support_url}\n\n"
    plain += (
        "If you did not request this recovery or believe the outcome is incorrect, "
        "contact PesaGuard support."
    )

    notice_kind = "error" if str(outcome).lower() in {"denied", "rejected"} else "info"

    html, plain_text = render_shell(
        preheader=f"Your account recovery request has been {outcome}",
        subject=f"PesaGuard account recovery {outcome}",
        category="Recovery",
        headline="Account recovery " + str(outcome),
        body_html=body,
        icon=icon_for("recovery"),
        cta_label="Contact support",
        cta_url=support_url,
        notice_html=(
            "If you did not request this recovery, contact PesaGuard support "
            "immediately."
        ),
        notice_kind=notice_kind,
        plain_text=plain,
    )
    return (
        html,
        plain_text,
        "Contact support",
        support_url,
        None,
        notice_kind,
        None,
    )


REGISTRY.register(TEMPLATE)


def register_renderer() -> None:
    from ...dispatcher import _RENDERERS

    _RENDERERS[TEMPLATE_ID] = _render


register_renderer()

__all__ = ["TEMPLATE", "TEMPLATE_ID", "TRIGGER_EVENT", "_render"]
