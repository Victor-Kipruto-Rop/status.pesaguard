"""
E01 — High-risk activity detected email.

Trigger: verified risk event. Distinguishes suspected from confirmed fraud.
"""

from __future__ import annotations

from html import escape
from typing import Any

from ..base import detail_row, paragraph
from ...registry import REGISTRY, TemplateDefinition
from ...shell import render_shell
from ...urls import EmailUrls
from ...icons import icon_for
from ...brand import SENDER_SECURITY, SENDER_SUPPORT

TEMPLATE_ID = "E01"
TRIGGER_EVENT = "risk.alert_created"

TEMPLATE = TemplateDefinition(
    template_id=TEMPLATE_ID,
    name="High-risk activity detected",
    category="risk",
    version="1.0.0",
    trigger_event=TRIGGER_EVENT,
    recipient_type="tenant_admin",
    sender=SENDER_SECURITY,
    reply_to=SENDER_SUPPORT,
    subject_template="High-risk activity detected on {entity}",
    preheader_template="A {severity} risk alert requires your review",
    required_variables=("entity", "risk_category", "severity", "detected_at",
                        "review_url"),
    optional_variables=("display_name", "reference", "handling_status"),
    cta={"label": "Review risk alert", "url_key": "review_url"},
    sensitivity="confidential",
    preference_classification="essential",
    idempotency_strategy="event_idempotency_key",
    icon_category="fraud_risk",
)


def _render(context: dict[str, Any], urls: EmailUrls):
    entity = context.get("entity", "")
    risk_category = context.get("risk_category", "")
    severity = context.get("severity", "medium")
    detected_at = context.get("detected_at", "not specified")
    review_url = context.get("review_url")
    reference = context.get("reference")
    handling_status = context.get("handling_status", "under review")
    display_name = context.get("display_name")

    body = (
        paragraph(
            "Hello" + (", " + escape(str(display_name)) if display_name else "") +
            ", high-risk activity has been detected on " + escape(str(entity)) + "."
        )
        + '<table width="100%">'
        + detail_row("Risk category", escape(str(risk_category)))
        + detail_row("Severity", str(severity).title())
        + detail_row("Detected at", str(detected_at))
        + detail_row("Handling status", escape(str(handling_status)))
        + ("<tr><td style=\"padding:8px 0;border-bottom:1px solid #EEF2EF;"
           "color:#6B7280;font-size:13.5px;\">Reference</td><td style=\"padding:8px 0;"
           "border-bottom:1px solid #EEF2EF;color:#0B2E24;font-size:13.5px;"
           "font-weight:600;text-align:right;\">" + escape(str(reference)) +
           "</td></tr>" if reference else "")
        + "</table>"
    )
    body += paragraph(
        "This is a suspected risk signal, not a confirmed fraud finding. Review "
        "the alert in the dashboard before taking any action."
    )

    plain = (
        f"Hello{', ' + escape(str(display_name)) if display_name else ''}, high-risk "
        f"activity has been detected on {entity}.\n\n"
        f"Risk category: {risk_category}\n"
        f"Severity: {severity}\n"
        f"Detected at: {detected_at}\n"
        f"Handling status: {handling_status}\n"
    )
    if reference:
        plain += f"Reference: {reference}\n"
    plain += f"Review: {review_url}\n\n"
    plain += (
        "This is a suspected risk signal, not a confirmed fraud finding."
    )

    html, plain_text = render_shell(
        preheader=f"A {severity} risk alert requires your review",
        subject=f"High-risk activity detected on {entity}",
        category="Fraud / risk",
        headline="High-risk activity detected",
        body_html=body,
        icon=icon_for("fraud_risk"),
        cta_label="Review risk alert",
        cta_url=review_url,
        notice_html=(
            "This is a suspected risk signal, not a confirmed fraud finding."
        ),
        notice_kind="warn",
        plain_text=plain,
    )
    return (
        html,
        plain_text,
        "Review risk alert",
        review_url,
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
