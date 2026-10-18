"""
A23 — Account deletion completed email.

Trigger: account deletion completed. Accurate scope, retention statement only
per actual policy, no misleading claims.
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

TEMPLATE_ID = "A23"
TRIGGER_EVENT = "auth.account_deletion_completed"

TEMPLATE = TemplateDefinition(
    template_id=TEMPLATE_ID,
    name="Account deletion completed",
    category="authentication",
    version="1.0.0",
    trigger_event=TRIGGER_EVENT,
    recipient_type="account_owner",
    sender=SENDER_SECURITY,
    reply_to=SENDER_SUPPORT,
    subject_template="Your PesaGuard account has been deleted",
    preheader_template="Account deletion completed at {completed_at}",
    required_variables=("account_email", "completed_at"),
    optional_variables=(
        "display_name",
        "retention_summary",
        "privacy_contact",
    ),
    sensitivity="confidential",
    preference_classification="essential",
    idempotency_strategy="event_idempotency_key",
    icon_category="administrative",
)


def _render(context: dict[str, Any], urls: EmailUrls):
    account_email = context.get("account_email", "")
    display_name = context.get("display_name") or account_email
    completed_at = context.get("completed_at", "not specified")
    retention_summary = context.get("retention_summary")
    privacy_contact = context.get("privacy_contact") or "privacy@pesaguard.co.ke"

    body = (
        paragraph(
            "Hello " + escape(str(display_name)) + ", your PesaGuard account has "
            "been deleted."
        )
        + '<table width="100%">'
        + detail_row("Email address", mask_email(account_email))
        + detail_row("Completed at", str(completed_at))
        + "</table>"
    )
    if retention_summary:
        body += detail_row("Data retention", str(retention_summary))
    body += paragraph(
        "Your account and associated data have been deleted in accordance with "
        "PesaGuard's retention policy. Some information may be retained as "
        "required by law."
    )

    plain = (
        f"Hello {escape(str(display_name))}, your PesaGuard account has been "
        f"deleted.\n\n"
        f"Email address: {account_email}\n"
        f"Completed at: {completed_at}\n"
    )
    if retention_summary:
        plain += f"Data retention: {retention_summary}\n"
    plain += (
        "Your account and associated data have been deleted in accordance with "
        "PesaGuard's retention policy. Some information may be retained as "
        "required by law.\n\n"
        f"Privacy contact: {privacy_contact}"
    )

    html, plain_text = render_shell(
        preheader=f"Account deletion completed at {completed_at}",
        subject="Your PesaGuard account has been deleted",
        category="Recovery",
        headline="Your account has been deleted",
        body_html=body,
        icon=icon_for("administrative"),
        notice_html=(
            "Some information may be retained as required by law or operational "
            "policy."
        ),
        notice_kind="info",
        plain_text=plain,
    )
    return (html, plain_text, None, None, None, "info", None)


REGISTRY.register(TEMPLATE)


def register_renderer() -> None:
    from ...dispatcher import _RENDERERS

    _RENDERERS[TEMPLATE_ID] = _render


register_renderer()

__all__ = ["TEMPLATE", "TEMPLATE_ID", "TRIGGER_EVENT", "_render"]
