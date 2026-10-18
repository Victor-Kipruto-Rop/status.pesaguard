"""
A17 — Email address changed email.

Trigger: email change completed. Confirmation with masked old/new addresses.
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

TEMPLATE_ID = "A17"
TRIGGER_EVENT = "auth.email_changed"

TEMPLATE = TemplateDefinition(
    template_id=TEMPLATE_ID,
    name="Email address changed",
    category="authentication",
    version="1.0.0",
    trigger_event=TRIGGER_EVENT,
    recipient_type="account_owner",
    sender=SENDER_SECURITY,
    reply_to=SENDER_SUPPORT,
    subject_template="Your PesaGuard email address was changed",
    preheader_template="Your email address changed at {changed_at}",
    required_variables=("old_email", "new_email", "changed_at"),
    optional_variables=("display_name", "security_url"),
    cta={"label": "Review account security", "url_key": "security_url"},
    sensitivity="internal",
    preference_classification="essential",
    idempotency_strategy="event_idempotency_key",
    icon_category="account_access",
)


def _render(context: dict[str, Any], urls: EmailUrls):
    old_email = context.get("old_email", "")
    new_email = context.get("new_email", "")
    display_name = context.get("display_name") or new_email
    changed_at = context.get("changed_at", "not specified")
    security_url = context.get("security_url") or urls.security_settings()

    body = (
        paragraph(
            "Hello " + escape(str(display_name)) + ", your PesaGuard account email "
            "address was changed."
        )
        + '<table width="100%">'
        + detail_row("Previous email", mask_email(old_email))
        + detail_row("New email", mask_email(new_email))
        + detail_row("Changed at", str(changed_at))
        + "</table>"
    )
    body += paragraph(
        "If you did not change your email address, secure your account immediately "
        "and contact PesaGuard support."
    )

    plain = (
        f"Hello {escape(str(display_name))}, your PesaGuard account email address "
        f"was changed.\n\n"
        f"Previous email: {old_email}\n"
        f"New email: {new_email}\n"
        f"Changed at: {changed_at}\n"
        f"Review security: {security_url}\n\n"
        "If you did not change your email address, secure your account immediately."
    )

    html, plain_text = render_shell(
        preheader=f"Your email address changed at {changed_at}",
        subject="Your PesaGuard email address was changed",
        category="Account access",
        headline="Your email address was changed",
        body_html=body,
        icon=icon_for("account_access"),
        cta_label="Review account security",
        cta_url=security_url,
        notice_html=(
            "If this was not you, change your password and contact support."
        ),
        notice_kind="error",
        plain_text=plain,
    )
    return (
        html,
        plain_text,
        "Review account security",
        security_url,
        None,
        "error",
        None,
    )


REGISTRY.register(TEMPLATE)


def register_renderer() -> None:
    from ...dispatcher import _RENDERERS

    _RENDERERS[TEMPLATE_ID] = _render


register_renderer()

__all__ = ["TEMPLATE", "TEMPLATE_ID", "TRIGGER_EVENT", "_render"]
