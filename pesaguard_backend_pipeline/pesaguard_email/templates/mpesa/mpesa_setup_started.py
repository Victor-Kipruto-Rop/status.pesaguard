"""
C01 — M-Pesa integration setup started email.

Trigger: M-Pesa integration setup started. Warns not to send credentials by email.
"""

from __future__ import annotations

from html import escape
from typing import Any

from ..base import detail_row, paragraph
from ...registry import REGISTRY, TemplateDefinition
from ...shell import render_shell
from ...urls import EmailUrls
from ...icons import icon_for
from ...brand import SENDER_NO_REPLY, SENDER_SUPPORT

TEMPLATE_ID = "C01"
TRIGGER_EVENT = "integration.setup_started"

TEMPLATE = TemplateDefinition(
    template_id=TEMPLATE_ID,
    name="M-Pesa integration setup started",
    category="integrations",
    version="1.0.0",
    trigger_event=TRIGGER_EVENT,
    recipient_type="tenant_admin",
    sender=SENDER_NO_REPLY,
    reply_to=SENDER_SUPPORT,
    subject_template="M-Pesa integration setup started",
    preheader_template="Set up your M-Pesa integration on PesaGuard",
    required_variables=("environment", "dashboard_url"),
    optional_variables=("display_name", "tenant_name"),
    cta={"label": "Configure integration", "url_key": "dashboard_url"},
    sensitivity="internal",
    preference_classification="operational",
    idempotency_strategy="event_idempotency_key",
    icon_category="mpesa_integration",
)


def _render(context: dict[str, Any], urls: EmailUrls):
    environment = context.get("environment", "sandbox")
    display_name = context.get("display_name")
    tenant_name = context.get("tenant_name")
    dashboard_url = context.get("dashboard_url") or urls.integrations()

    body = (
        paragraph(
            "Hello" + (", " + escape(str(display_name)) if display_name else "") +
            ", your M-Pesa integration setup has started."
        )
        + '<table width="100%">'
        + detail_row("Integration", "M-Pesa (Daraja)")
        + detail_row("Environment", str(environment).title())
        + detail_row("Status", "Setup started")
        + ("<tr><td style=\"padding:8px 0;border-bottom:1px solid #EEF2EF;"
           "color:#6B7280;font-size:13.5px;\">Tenant</td><td style=\"padding:8px 0;"
           "border-bottom:1px solid #EEF2EF;color:#0B2E24;font-size:13.5px;"
           "font-weight:600;text-align:right;\">" + escape(str(tenant_name)) +
           "</td></tr>" if tenant_name else "")
        + "</table>"
    )
    body += paragraph(
        "Complete the configuration in the dashboard. Never send API credentials, "
        "consumer secrets, or passwords by email."
    )

    plain = (
        f"Hello{', ' + escape(str(display_name)) if display_name else ''}, your "
        f"M-Pesa integration setup has started.\n\n"
        f"Integration: M-Pesa (Daraja)\n"
        f"Environment: {environment}\n"
        f"Status: Setup started\n"
        f"Configure: {dashboard_url}\n\n"
        "Never send API credentials, consumer secrets, or passwords by email."
    )

    html, plain_text = render_shell(
        preheader="Set up your M-Pesa integration on PesaGuard",
        subject="M-Pesa integration setup started",
        category="M-Pesa integration",
        headline="M-Pesa integration setup started",
        body_html=body,
        icon=icon_for("mpesa_integration"),
        cta_label="Configure integration",
        cta_url=dashboard_url,
        notice_html=(
            "Never send API credentials, consumer secrets, or passwords by email."
        ),
        notice_kind="warn",
        plain_text=plain,
    )
    return (
        html,
        plain_text,
        "Configure integration",
        dashboard_url,
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
