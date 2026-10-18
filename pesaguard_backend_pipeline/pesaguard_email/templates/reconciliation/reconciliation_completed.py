"""
D02 — Reconciliation completed successfully email.

Trigger: reconciliation completed. All figures from the actual result.
"""

from __future__ import annotations

from html import escape
from typing import Any

from ..base import detail_row, format_currency, paragraph
from ...registry import REGISTRY, TemplateDefinition
from ...shell import render_shell
from ...urls import EmailUrls
from ...icons import icon_for
from ...brand import SENDER_NO_REPLY, SENDER_SUPPORT

TEMPLATE_ID = "D02"
TRIGGER_EVENT = "reconciliation.completed"

TEMPLATE = TemplateDefinition(
    template_id="D02",
    name="Reconciliation completed successfully",
    category="reconciliation",
    version="1.0.0",
    trigger_event=TRIGGER_EVENT,
    recipient_type="tenant_admin",
    sender=SENDER_NO_REPLY,
    reply_to=SENDER_SUPPORT,
    subject_template="Reconciliation completed for {period}",
    preheader_template="Reconciliation {reference} finished processing",
    required_variables=("reference", "period", "completed_at", "records_processed",
                        "matched_count", "unmatched_count", "exception_count"),
    optional_variables=("display_name", "dashboard_url", "report_url"),
    cta={"label": "Review reconciliation result", "url_key": "dashboard_url"},
    sensitivity="internal",
    preference_classification="operational",
    idempotency_strategy="event_idempotency_key",
    icon_category="reconciliation",
)


def _render(context: dict[str, Any], urls: EmailUrls):
    reference = context.get("reference", "")
    period = context.get("period", "")
    completed_at = context.get("completed_at", "not specified")
    records_processed = context.get("records_processed", 0)
    matched_count = context.get("matched_count", 0)
    unmatched_count = context.get("unmatched_count", 0)
    exception_count = context.get("exception_count", 0)
    display_name = context.get("display_name")
    dashboard_url = context.get("dashboard_url") or urls.reconciliation()
    report_url = context.get("report_url")

    body = (
        paragraph(
            "Hello" + (", " + escape(str(display_name)) if display_name else "") +
            ", your PesaGuard reconciliation has completed successfully."
        )
        + '<table width="100%">'
        + detail_row("Reference", escape(str(reference)))
        + detail_row("Reporting period", escape(str(period)))
        + detail_row("Completed at", str(completed_at))
        + detail_row("Records processed", str(records_processed))
        + detail_row("Matched", str(matched_count))
        + detail_row("Unmatched", str(unmatched_count))
        + detail_row("Exceptions", str(exception_count))
        + "</table>"
    )
    body += paragraph(
        "Review the matched, unmatched, and exception records in the dashboard. "
        "Unmatched transactions do not automatically indicate fraud."
    )

    plain = (
        f"Hello{', ' + escape(str(display_name)) if display_name else ''}, your "
        f"PesaGuard reconciliation has completed successfully.\n\n"
        f"Reference: {reference}\n"
        f"Reporting period: {period}\n"
        f"Completed at: {completed_at}\n"
        f"Records processed: {records_processed}\n"
        f"Matched: {matched_count}\n"
        f"Unmatched: {unmatched_count}\n"
        f"Exceptions: {exception_count}\n"
        f"Review: {dashboard_url}\n\n"
        "Unmatched transactions do not automatically indicate fraud."
    )

    cta_url = report_url or dashboard_url
    html, plain_text = render_shell(
        preheader=f"Reconciliation {reference} finished processing",
        subject=f"Reconciliation completed for {period}",
        category="Reconciliation",
        headline="Reconciliation completed",
        body_html=body,
        icon=icon_for("reconciliation"),
        cta_label="Review reconciliation result",
        cta_url=cta_url,
        notice_html=(
            "Unmatched transactions do not automatically indicate fraud."
        ),
        notice_kind="info",
        plain_text=plain,
    )
    return (
        html,
        plain_text,
        "Review reconciliation result",
        cta_url,
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
