"""
F08 — API key created email.

Trigger: API key created. Never sends the complete API secret.
"""

from __future__ import annotations

from html import escape
from typing import Any

from ..base import detail_row, mask_reference, paragraph
from ...registry import REGISTRY, TemplateDefinition
from ...shell import render_shell
from ...urls import EmailUrls
from ...icons import icon_for
from ...brand import SENDER_DEVELOPERS, SENDER_SUPPORT

TEMPLATE_ID = "F08"
TRIGGER_EVENT = "developer.api_key_created"

TEMPLATE = TemplateDefinition(
    template_id=TEMPLATE_ID,
    name="API key created",
    category="developer",
    version="1.0.0",
    trigger_event=TRIGGER_EVENT,
    recipient_type="developer",
    sender=SENDER_DEVELOPERS,
    reply_to=SENDER_SUPPORT,
    subject_template="A new API key was created for {project}",
    preheader_template="API key {key_label} was created in {environment}",
    required_variables=("project", "environment", "key_label", "key_id",
                        "created_at"),
    optional_variables=("display_name", "dashboard_url"),
    cta={"label": "Manage API keys", "url_key": "dashboard_url"},
    sensitivity="confidential",
    preference_classification="essential",
    idempotency_strategy="event_idempotency_key",
    icon_category="api_key",
)


def _render(context: dict[str, Any], urls: EmailUrls):
    project = context.get("project", "")
    environment = context.get("environment", "sandbox")
    key_label = context.get("key_label", "")
    key_id = context.get("key_id", "")
    created_at = context.get("created_at", "not specified")
    display_name = context.get("display_name")
    dashboard_url = context.get("dashboard_url") or urls.developer_api_keys(
        str(context.get("project_id", ""))
    )

    body = (
        paragraph(
            "Hello" + (", " + escape(str(display_name)) if display_name else "") +
            ", a new API key was created for your PesaGuard project."
        )
        + '<table width="100%">'
        + detail_row("Project", escape(str(project)))
        + detail_row("Environment", str(environment).title())
        + detail_row("Key label", escape(str(key_label)))
        + detail_row("Key ID", mask_reference(str(key_id)))
        + detail_row("Created at", str(created_at))
        + "</table>"
    )
    body += paragraph(
        "The API secret was shown only once when the key was created and is not "
        "included in this email. Store it securely. Never send API secrets by "
        "email."
    )

    plain = (
        f"Hello{', ' + escape(str(display_name)) if display_name else ''}, a new "
        f"API key was created for your PesaGuard project.\n\n"
        f"Project: {project}\n"
        f"Environment: {environment}\n"
        f"Key label: {key_label}\n"
        f"Key ID: {mask_reference(str(key_id))}\n"
        f"Created at: {created_at}\n"
        f"Manage: {dashboard_url}\n\n"
        "The API secret was shown only once when the key was created and is not "
        "included in this email. Never send API secrets by email."
    )

    html, plain_text = render_shell(
        preheader=f"API key {key_label} was created in {environment}",
        subject=f"A new API key was created for {project}",
        category="API key",
        headline="API key created",
        body_html=body,
        icon=icon_for("api_key"),
        cta_label="Manage API keys",
        cta_url=dashboard_url,
        notice_html=(
            "The API secret is not included in this email. Never send API secrets "
            "by email."
        ),
        notice_kind="warn",
        plain_text=plain,
    )
    return (
        html,
        plain_text,
        "Manage API keys",
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
