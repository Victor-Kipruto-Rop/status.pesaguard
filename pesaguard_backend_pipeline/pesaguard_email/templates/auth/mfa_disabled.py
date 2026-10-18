"""
A12 — MFA disabled email.

Trigger: MFA was disabled. Confirms the change and states security consequences.
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

TEMPLATE_ID = "A12"
TRIGGER_EVENT = "auth.mfa_disabled"

TEMPLATE = TemplateDefinition(
    template_id=TEMPLATE_ID,
    name="MFA disabled",
    category="authentication",
    version="1.0.0",
    trigger_event=TRIGGER_EVENT,
    recipient_type="account_owner",
    sender=SENDER_SECURITY,
    reply_to=SENDER_SUPPORT,
    subject_template="Multi-factor authentication was disabled",
    preheader_template="MFA is no longer active on your PesaGuard account",
    required_variables=("account_email", "disabled_at"),
    optional_variables=("display_name", "security_url"),
    cta={"label": "Review security settings", "url_key": "security_url"},
    sensitivity="internal",
    preference_classification="essential",
    idempotency_strategy="event_idempotency_key",
    icon_category="account_access",
)


def _render(context: dict[str, Any], urls: EmailUrls):
    account_email = context.get("account_email", "")
    display_name = context.get("display_name") or account_email
    disabled_at = context.get("disabled_at", "not specified")
    security_url = context.get("security_url") or urls.security_settings()

    body = (
        paragraph(
            "Hello " + escape(str(display_name)) + ", multi-factor authentication "
            "(MFA) has been disabled on your PesaGuard account."
        )
        + '<table width="100%">'
        + detail_row("Email address", mask_email(account_email))
        + detail_row("Disabled at", str(disabled_at))
        + "</table>"
    )
    body += paragraph(
        "Disabling MFA reduces the security of your account. Sign-in now requires "
        "only your password. If you did not disable MFA, secure your account "
        "immediately and contact PesaGuard support."
    )

    plain = (
        f"Hello {escape(str(display_name))}, multi-factor authentication (MFA) has "
        f"been disabled on your PesaGuard account.\n\n"
        f"Email address: {account_email}\n"
        f"Disabled at: {disabled_at}\n"
        f"Review security: {security_url}\n\n"
        "Disabling MFA reduces the security of your account."
    )

    html, plain_text = render_shell(
        preheader="MFA is no longer active on your PesaGuard account",
        subject="Multi-factor authentication was disabled",
        category="Account access",
        headline="MFA was disabled",
        body_html=body,
        icon=icon_for("account_access"),
        cta_label="Review security settings",
        cta_url=security_url,
        notice_html=(
            "If you did not disable MFA, secure your account immediately and "
            "contact support."
        ),
        notice_kind="error",
        plain_text=plain,
    )
    return (
        html,
        plain_text,
        "Review security settings",
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
