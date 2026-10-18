"""
H01 — Daily activity digest email.

Trigger: scheduled daily digest. Summary of key activity for the reporting date.
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

TEMPLATE_ID = "H01"
TRIGGER_EVENT = "report.daily_digest"

TEMPLATE = TemplateDefinition(
    template_id=TEMPLATE_ID,
    name="Daily activity digest",
    category="reports",
    version="1.0.0",
    trigger_event=TRIGGER_EVENT,
    recipient_type="tenant_admin",
    sender=SENDER_NO_REPLY,
    reply_to=SENDER_SUPPORT,
    subject_template="PesaGuard daily activity digest — {report_date}",
    preheader_template="Activity summary for {report_date}",
    required_variables=("report_date", "transaction_count", "reconciliation_status",
                        "exception_count"),
    optional_variables=("display_name", "dashboard_url", "risk_alert_count"),
    cta={"label": "View activity dashboard", "url_key": "dashboard_url"},
    sensitivity="internal",
    preference_classification="operational",
    idempotency_strategy="event_idempotency_key",
    icon_category="reports",
)


def _render(context: dict[str, Any], urls: EmailUrls):
    report_date = context.get("report_date", "")
    transaction_count = context.get("transaction_count", 0)
    reconciliation_status = context.get("reconciliation_status", "up to date")
    exception_count = context.get("exception_count", 0)
    display_name = context.get("display_name")
    dashboard_url = context.get("dashboard_url") or urls.dashboard_home()
    risk_alert_count = context.get("risk_alert_count", 0)

    body = (
        paragraph(
            "Hello" + (", " + escape(str(display_name)) if display_name else "") +
            ", here is your PesaGuard activity summary for " +
            escape(str(report_date)) + "."
        )
        + '<table width="100%">'
        + detail_row("Report date", escape(str(report_date)))
        + detail_row("Transactions", str(transaction_count))
        + detail_row("Reconciliation", escape(str(reconciliation_status)))
        + detail_row("Open exceptions", str(exception_count))
        + detail_row("Risk alerts", str(risk_alert_count))
        + "</table>"
    )
    body += paragraph(
        "Review exceptions and risk alerts in the dashboard. Unmatched "
        "transactions do not automatically indicate fraud."
    )

    plain = (
        f"Hello{', ' + escape(str(display_name)) if display_name else ''}, here is "
        f"your PesaGuard activity summary for {report_date}.\n\n"
        f"Report date: {report_date}\n"
        f"Transactions: {transaction_count}\n"
        f"Reconciliation: {reconciliation_status}\n"
        f"Open exceptions: {exception_count}\n"
        f"Risk alerts: {risk_alert_count}\n"
        f"Dashboard: {dashboard_url}\n\n"
        "Review exceptions and risk alerts in the dashboard."
    )

    html, plain_text = render_shell(
        preheader=f"Activity summary for {report_date}",
        subject=f"PesaGuard daily activity digest — {report_date}",
        category="Reports",
        headline="Daily activity digest",
        body_html=body,
        icon=icon_for("reports"),
        cta_label="View activity dashboard",
        cta_url=dashboard_url,
        notice_html=(
            "Unmatched transactions do not automatically indicate fraud."
        ),
        notice_kind="info",
        plain_text=plain,
    )
    return (
        html,
        plain_text,
        "View activity dashboard",
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
