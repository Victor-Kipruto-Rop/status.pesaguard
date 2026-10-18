"""
B08 — Onboarding approved email.

Trigger: onboarding review approved. Confirms approval and next setup steps.
"""

from __future__ import annotations

from html import escape
from typing import Any

from ..base import detail_row, paragraph
from ...registry import REGISTRY, TemplateDefinition
from ...shell import render_shell
from ...urls import EmailUrls
from ...icons import icon_for
from ...brand import SENDER_ONBOARDING, SENDER_SUPPORT

TEMPLATE_ID = "B08"
TRIGGER_EVENT = "tenant.approved"

TEMPLATE = TemplateDefinition(
    template_id=TEMPLATE_ID,
    name="Onboarding approved",
    category="onboarding",
    version="1.0.0",
    trigger_event=TRIGGER_EVENT,
    recipient_type="tenant_admin",
    sender=SENDER_ONBOARDING,
    reply_to=SENDER_SUPPORT,
    subject_template="Your PesaGuard onboarding has been approved",
    preheader_template="{organization} is now approved on PesaGuard",
    required_variables=("organization", "approved_at"),
    optional_variables=("display_name", "dashboard_url", "next_steps"),
    cta={"label": "Go to dashboard", "url_key": "dashboard_url"},
    sensitivity="internal",
    preference_classification="essential",
    idempotency_strategy="event_idempotency_key",
    icon_category="approval",
)


def _render(context: dict[str, Any], urls: EmailUrls):
    organization = context.get("organization", "")
    display_name = context.get("display_name")
    approved_at = context.get("approved_at", "not specified")
    dashboard_url = context.get("dashboard_url") or urls.dashboard_home()
    next_steps = context.get("next_steps", "Set up your M-Pesa integration to begin reconciling transactions.")

    body = (
        paragraph(
            "Hello" + (", " + escape(str(display_name)) if display_name else "") +
            ", your PesaGuard onboarding has been approved."
        )
        + '<table width="100%">'
        + detail_row("Organization", escape(str(organization)))
        + detail_row("Approved at", str(approved_at))
        + detail_row("Status", "Approved")
        + "</table>"
    )
    body += paragraph(
        "You can now sign in to the dashboard and begin setting up your "
        "integrations. " + str(next_steps)
    )

    plain = (
        f"Hello{', ' + escape(str(display_name)) if display_name else ''}, your "
        f"PesaGuard onboarding has been approved.\n\n"
        f"Organization: {organization}\n"
        f"Approved at: {approved_at}\n"
        f"Status: Approved\n"
        f"Next steps: {next_steps}\n"
        f"Dashboard: {dashboard_url}\n"
    )

    html, plain_text = render_shell(
        preheader=f"{organization} is now approved on PesaGuard",
        subject="Your PesaGuard onboarding has been approved",
        category="Approval",
        headline="Onboarding approved",
        body_html=body,
        icon=icon_for("approval"),
        cta_label="Go to dashboard",
        cta_url=dashboard_url,
        notice_html=(
            "Set up your M-Pesa integration to begin reconciling transactions."
        ),
        notice_kind="info",
        plain_text=plain,
    )
    return (
        html,
        plain_text,
        "Go to dashboard",
        dashboard_url,
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
