"""
A11 — MFA enabled email.

Trigger: MFA enrollment succeeded. Method category only, no secrets exposed.
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

TEMPLATE_ID = "A11"
TRIGGER_EVENT = "auth.mfa_enabled"

TEMPLATE = TemplateDefinition(
    template_id=TEMPLATE_ID,
    name="MFA enabled",
    category="authentication",
    version="1.0.0",
    trigger_event=TRIGGER_EVENT,
    recipient_type="account_owner",
    sender=SENDER_SECURITY,
    reply_to=SENDER_SUPPORT,
    subject_template="Multi-factor authentication was enabled",
    preheader_template="MFA is now active on your PesaGuard account",
    required_variables=("account_email", "mfa_method", "enabled_at"),
    optional_variables=("display_name", "recovery_info", "security_url"),
    cta={"label": "Review security settings", "url_key": "security_url"},
    sensitivity="internal",
    preference_classification="essential",
    idempotency_strategy="event_idempotency_key",
    icon_category="account_access",
)


def _render(context: dict[str, Any], urls: EmailUrls):
    account_email = context.get("account_email", "")
    display_name = context.get("display_name") or account_email
    mfa_method = context.get("mfa_method", "authenticator app")
    enabled_at = context.get("enabled_at", "not specified")
    recovery_info = context.get("recovery_info")
    security_url = context.get("security_url") or urls.security_settings()

    body = (
        paragraph(
            "Hello " + escape(str(display_name)) + ", multi-factor authentication "
            "(MFA) has been enabled on your PesaGuard account."
        )
        + '<table width="100%">'
        + detail_row("Email address", mask_email(account_email))
        + detail_row("MFA method", escape(str(mfa_method)))
        + detail_row("Enabled at", str(enabled_at))
        + "</table>"
    )
    if recovery_info:
        body += detail_row("Recovery method", escape(str(recovery_info)))
    body += paragraph(
        "MFA adds a second verification step when you sign in. Keep your recovery "
        "method in a safe place. PesaGuard will never ask for your MFA code by "
        "email."
    )

    plain = (
        f"Hello {escape(str(display_name))}, multi-factor authentication (MFA) has "
        f"been enabled on your PesaGuard account.\n\n"
        f"Email address: {account_email}\n"
        f"MFA method: {mfa_method}\n"
        f"Enabled at: {enabled_at}\n"
    )
    if recovery_info:
        plain += f"Recovery method: {recovery_info}\n"
    plain += (
        f"Review security: {security_url}\n\n"
        "MFA adds a second verification step when you sign in. PesaGuard will never "
        "ask for your MFA code by email."
    )

    html, plain_text = render_shell(
        preheader="MFA is now active on your PesaGuard account",
        subject="Multi-factor authentication was enabled",
        category="Account access",
        headline="MFA was enabled",
        body_html=body,
        icon=icon_for("account_access"),
        cta_label="Review security settings",
        cta_url=security_url,
        notice_html=(
            "Keep your recovery method in a safe place. PesaGuard will never ask "
            "for your MFA code by email."
        ),
        notice_kind="info",
        plain_text=plain,
    )
    return (
        html,
        plain_text,
        "Review security settings",
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
