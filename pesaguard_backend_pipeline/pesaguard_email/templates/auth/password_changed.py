"""
A08 — Password changed email.

Trigger: password-change operation succeeded. Never sends the password itself.
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

TEMPLATE_ID = "A08"
TRIGGER_EVENT = "auth.password_changed"

TEMPLATE = TemplateDefinition(
    template_id=TEMPLATE_ID,
    name="Password changed",
    category="authentication",
    version="1.0.0",
    trigger_event=TRIGGER_EVENT,
    recipient_type="account_owner",
    sender=SENDER_SECURITY,
    reply_to=SENDER_SUPPORT,
    subject_template="Your PesaGuard password was changed",
    preheader_template="Your password was changed at {changed_at}",
    required_variables=("account_email", "changed_at"),
    optional_variables=("display_name", "sessions_revoked", "security_url"),
    cta={"label": "Review account security", "url_key": "security_url"},
    sensitivity="internal",
    preference_classification="essential",
    idempotency_strategy="event_idempotency_key",
    icon_category="account_access",
)


def _render(context: dict[str, Any], urls: EmailUrls):
    account_email = context.get("account_email", "")
    display_name = context.get("display_name") or account_email
    changed_at = context.get("changed_at", "not specified")
    sessions_revoked = bool(context.get("sessions_revoked", False))
    security_url = context.get("security_url") or urls.security_settings()

    body = (
        paragraph(
            "Hello " + escape(str(display_name)) + ", your PesaGuard account "
            "password was changed successfully."
        )
        + '<table width="100%">'
        + detail_row("Email address", mask_email(account_email))
        + detail_row("Changed at", str(changed_at))
        + detail_row(
            "Sessions revoked",
            "Yes — all other sessions were signed out" if sessions_revoked else "No",
        )
        + "</table>"
    )
    body += paragraph(
        "If you did not change your password, secure your account immediately and "
        "contact PesaGuard support."
    )

    plain = (
        f"Hello {escape(str(display_name))}, your PesaGuard account password was "
        f"changed successfully.\n\n"
        f"Email address: {account_email}\n"
        f"Changed at: {changed_at}\n"
        f"Sessions revoked: {'Yes' if sessions_revoked else 'No'}\n"
        f"Review security: {security_url}\n\n"
        "If you did not change your password, secure your account immediately."
    )

    html, plain_text = render_shell(
        preheader=f"Your password was changed at {changed_at}",
        subject="Your PesaGuard password was changed",
        category="Account access",
        headline="Your password was changed",
        body_html=body,
        icon=icon_for("account_access"),
        cta_label="Review account security",
        cta_url=security_url,
        notice_html=(
            "If this was not you, change your password again and contact support."
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
