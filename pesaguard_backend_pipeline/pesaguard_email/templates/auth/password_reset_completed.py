"""
A09 — Password reset completed email.

Trigger: recovery succeeded. No sensitive token data is included.
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

TEMPLATE_ID = "A09"
TRIGGER_EVENT = "auth.password_reset_completed"

TEMPLATE = TemplateDefinition(
    template_id=TEMPLATE_ID,
    name="Password reset completed",
    category="authentication",
    version="1.0.0",
    trigger_event=TRIGGER_EVENT,
    recipient_type="account_owner",
    sender=SENDER_SECURITY,
    reply_to=SENDER_SUPPORT,
    subject_template="Your PesaGuard password reset is complete",
    preheader_template="Your password was reset at {reset_at}",
    required_variables=("account_email", "reset_at"),
    optional_variables=("display_name", "security_url"),
    cta={"label": "Review account security", "url_key": "security_url"},
    sensitivity="internal",
    preference_classification="essential",
    idempotency_strategy="event_idempotency_key",
    icon_category="recovery",
)


def _render(context: dict[str, Any], urls: EmailUrls):
    account_email = context.get("account_email", "")
    display_name = context.get("display_name") or account_email
    reset_at = context.get("reset_at", "not specified")
    security_url = context.get("security_url") or urls.security_settings()

    body = (
        paragraph(
            "Hello " + escape(str(display_name)) + ", your PesaGuard account "
            "password reset is complete."
        )
        + '<table width="100%">'
        + detail_row("Email address", mask_email(account_email))
        + detail_row("Reset completed at", str(reset_at))
        + "</table>"
    )
    body += paragraph(
        "If you did not perform this reset, secure your account immediately and "
        "contact PesaGuard support. No reset token or code is included in this "
        "message."
    )

    plain = (
        f"Hello {escape(str(display_name))}, your PesaGuard account password reset "
        f"is complete.\n\n"
        f"Email address: {account_email}\n"
        f"Reset completed at: {reset_at}\n"
        f"Review security: {security_url}\n\n"
        "If you did not perform this reset, secure your account immediately."
    )

    html, plain_text = render_shell(
        preheader=f"Your password was reset at {reset_at}",
        subject="Your PesaGuard password reset is complete",
        category="Recovery",
        headline="Password reset complete",
        body_html=body,
        icon=icon_for("recovery"),
        cta_label="Review account security",
        cta_url=security_url,
        notice_html=(
            "No reset token or code is included in this message."
        ),
        notice_kind="info",
        plain_text=plain,
    )
    return (
        html,
        plain_text,
        "Review account security",
        security_url,
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
