"""
K22 — Incident opened email.

Trigger: critical operational incident opened. For authorized operational
recipients only.
"""

from __future__ import annotations

from html import escape
from typing import Any

from ..base import detail_row, paragraph
from ...registry import REGISTRY, TemplateDefinition
from ...shell import render_shell
from ...urls import EmailUrls
from ...icons import icon_for
from ...brand import SENDER_OPERATIONS, SENDER_INCIDENTS

TEMPLATE_ID = "K22"
TRIGGER_EVENT = "incident.opened"

TEMPLATE = TemplateDefinition(
    template_id=TEMPLATE_ID,
    name="Incident opened",
    category="operations",
    version="1.0.0",
    trigger_event=TRIGGER_EVENT,
    recipient_type="operations",
    sender=SENDER_OPERATIONS,
    reply_to=SENDER_INCIDENTS,
    subject_template="PesaGuard incident {severity}: {incident_reference}",
    preheader_template="Incident {incident_reference} opened — {severity}",
    required_variables=("incident_reference", "severity", "impacted_systems",
                        "opened_at", "response_channel"),
    optional_variables=("display_name", "incident_owner", "status_page_url"),
    cta={"label": "View status page", "url_key": "status_page_url"},
    sensitivity="restricted",
    preference_classification="essential",
    idempotency_strategy="event_idempotency_key",
    icon_category="incident",
)


def _render(context: dict[str, Any], urls: EmailUrls):
    incident_reference = context.get("incident_reference", "")
    severity = context.get("severity", "medium")
    impacted_systems = context.get("impacted_systems", "")
    opened_at = context.get("opened_at", "not specified")
    response_channel = context.get("response_channel", "")
    incident_owner = context.get("incident_owner")
    status_page_url = context.get("status_page_url") or urls.status_page()
    display_name = context.get("display_name")

    body = (
        paragraph(
            "Hello" + (", " + escape(str(display_name)) if display_name else "") +
            ", a PesaGuard incident has been opened."
        )
        + '<table width="100%">'
        + detail_row("Incident reference", escape(str(incident_reference)))
        + detail_row("Severity", str(severity).upper())
        + detail_row("Impacted systems", escape(str(impacted_systems)))
        + detail_row("Opened at", str(opened_at))
        + detail_row("Response channel", escape(str(response_channel)))
        + ("<tr><td style=\"padding:8px 0;border-bottom:1px solid #EEF2EF;"
           "color:#6B7280;font-size:13.5px;\">Incident owner</td><td style=\"padding:8px 0;"
           "border-bottom:1px solid #EEF2EF;color:#0B2E24;font-size:13.5px;"
           "font-weight:600;text-align:right;\">" + escape(str(incident_owner)) +
           "</td></tr>" if incident_owner else "")
        + "</table>"
    )
    body += paragraph(
        "This is an internal operational notification. Follow the incident "
        "response runbook and post updates through the response channel."
    )

    plain = (
        f"Hello{', ' + escape(str(display_name)) if display_name else ''}, a "
        f"PesaGuard incident has been opened.\n\n"
        f"Incident reference: {incident_reference}\n"
        f"Severity: {severity}\n"
        f"Impacted systems: {impacted_systems}\n"
        f"Opened at: {opened_at}\n"
        f"Response channel: {response_channel}\n"
    )
    if incident_owner:
        plain += f"Incident owner: {incident_owner}\n"
    plain += f"Status page: {status_page_url}\n\n"
    plain += (
        "Follow the incident response runbook and post updates through the "
        "response channel."
    )

    notice_kind = "error" if str(severity).lower() in {"critical", "high"} else "warn"

    html, plain_text = render_shell(
        preheader=f"Incident {incident_reference} opened — {severity}",
        subject=f"PesaGuard incident {severity}: {incident_reference}",
        category="Incident",
        headline="Incident opened",
        body_html=body,
        icon=icon_for("incident"),
        cta_label="View status page",
        cta_url=status_page_url,
        notice_html=(
            "This is an internal operational notification. Follow the incident "
            "response runbook."
        ),
        notice_kind=notice_kind,
        plain_text=plain,
    )
    return (
        html,
        plain_text,
        "View status page",
        status_page_url,
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
