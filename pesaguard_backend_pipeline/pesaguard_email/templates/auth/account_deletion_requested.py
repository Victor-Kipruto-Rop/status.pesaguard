"""
A22 — Account deletion requested email.

Trigger: account deletion requested. Confirms request, scope, and cancellation
route where supported. No claim that all backups are erased.
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

TEMPLATE_ID = "A22"
TRIGGER_EVENT = "auth.account_deletion_requested"

TEMPLATE = TemplateDefinition(
    template_id=TEMPLATE_ID,
    name="Account deletion requested",
    category="authentication",
    version="1.0.0",
    trigger_event=TRIGGER_EVENT,
    recipient_type="account_owner",
    sender=SENDER_SECURITY,
    reply_to=SENDER_SUPPORT,
    subject_template="PesaGuard account deletion requested",
    preheader_template="Your account deletion request was received",
    required_variables=("account_email", "requested_at"),
    optional_variables=(
        "display_name",
        "grace_period",
        "cancellation_url",
        "privacy_contact",
    ),
    cta={"label": "Cancel deletion", "url_key": "cancellation_url"},
    sensitivity="confidential",
    preference_classification="essential",
    idempotency_strategy="event_idempotency_key",
    icon_category="administrative",
)


def _render(context: dict[str, Any], urls: EmailUrls):
    account_email = context.get("account_email", "")
    display_name = context.get("display_name") or account_email
    requested_at = context.get("requested_at", "not specified")
    grace_period = context.get("grace_period")
    cancellation_url = context.get("cancellation_url")
    privacy_contact = context.get("privacy_contact") or "privacy@pesaguard.co.ke"

    body = (
        paragraph(
            "Hello " + escape(str(display_name)) + ", we received a request to "
            "delete your PesaGuard account."
        )
        + '<table width="100%">'
        + detail_row("Email address", mask_email(account_email))
        + detail_row("Requested at", str(requested_at))
    )
    if grace_period:
        body += detail_row("Cancellation deadline", str(grace_period))
    body += "</table>"
    body += paragraph(
        "Your account has not been deleted yet. If you change your mind, use the "
        "cancellation link below before the deadline."
    )

    plain = (
        f"Hello {escape(str(display_name))}, we received a request to delete your "
        f"PesaGuard account.\n\n"
        f"Email address: {account_email}\n"
        f"Requested at: {requested_at}\n"
    )
    if grace_period:
        plain += f"Cancellation deadline: {grace_period}\n"
    if cancellation_url:
        plain += f"Cancel deletion: {cancellation_url}\n"
    plain += (
        "Your account has not been deleted yet. If you change your mind, use the "
        "cancellation link before the deadline.\n\n"
        f"Privacy contact: {privacy_contact}"
    )

    html, plain_text = render_shell(
        preheader="Your account deletion request was received",
        subject="PesaGuard account deletion requested",
        category="Security alert",
        headline="Account deletion requested",
        body_html=body,
        icon=icon_for("administrative"),
        cta_label="Cancel deletion",
        cta_url=cancellation_url,
        notice_html=(
            "Your account has not been deleted yet. Some information may be "
            "retained as required by law or operational policy."
        ),
        notice_kind="warn",
        plain_text=plain,
    )
    return (
        html,
        plain_text,
        "Cancel deletion",
        cancellation_url,
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
