"""
A15 — Session revoked email.

Trigger: a session was revoked. States whether one session or all sessions were
affected.
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

TEMPLATE_ID = "A15"
TRIGGER_EVENT = "auth.session_revoked"

TEMPLATE = TemplateDefinition(
    template_id=TEMPLATE_ID,
    name="Session revoked",
    category="authentication",
    version="1.0.0",
    trigger_event=TRIGGER_EVENT,
    recipient_type="account_owner",
    sender=SENDER_SECURITY,
    reply_to=SENDER_SUPPORT,
    subject_template="PesaGuard session was revoked",
    preheader_template="A session for your account was signed out",
    required_variables=("account_email", "revoked_at"),
    optional_variables=(
        "display_name",
        "session_description",
        "all_sessions",
        "security_url",
    ),
    cta={"label": "Review active sessions", "url_key": "security_url"},
    sensitivity="internal",
    preference_classification="essential",
    idempotency_strategy="event_idempotency_key",
    icon_category="account_access",
)


def _render(context: dict[str, Any], urls: EmailUrls):
    account_email = context.get("account_email", "")
    display_name = context.get("display_name") or account_email
    revoked_at = context.get("revoked_at", "not specified")
    session_description = context.get("session_description")
    all_sessions = bool(context.get("all_sessions", False))
    security_url = context.get("security_url") or urls.security_settings()

    body = (
        paragraph(
            "Hello " + escape(str(display_name)) + ", a PesaGuard session was "
            "revoked for your account."
        )
        + '<table width="100%">'
        + detail_row("Email address", mask_email(account_email))
        + detail_row("Revoked at", str(revoked_at))
        + detail_row(
            "Scope",
            "All sessions" if all_sessions else "One session",
        )
    )
    if session_description:
        body += detail_row("Session", escape(str(session_description)))
    body += "</table>"
    body += paragraph(
        "If you did not revoke this session, secure your account immediately and "
        "contact PesaGuard support."
    )

    plain = (
        f"Hello {escape(str(display_name))}, a PesaGuard session was revoked for "
        f"your account.\n\n"
        f"Email address: {account_email}\n"
        f"Revoked at: {revoked_at}\n"
        f"Scope: {'All sessions' if all_sessions else 'One session'}\n"
    )
    if session_description:
        plain += f"Session: {session_description}\n"
    plain += (
        f"Review active sessions: {security_url}\n\n"
        "If you did not revoke this session, secure your account immediately."
    )

    html, plain_text = render_shell(
        preheader="A session for your account was signed out",
        subject="PesaGuard session was revoked",
        category="Account access",
        headline="Session revoked",
        body_html=body,
        icon=icon_for("account_access"),
        cta_label="Review active sessions",
        cta_url=security_url,
        notice_html=(
            "If you did not revoke this session, change your password and contact "
            "support."
        ),
        notice_kind="error",
        plain_text=plain,
    )
    return (
        html,
        plain_text,
        "Review active sessions",
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
