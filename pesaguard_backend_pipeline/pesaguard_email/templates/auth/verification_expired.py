"""
A04 — Verification link expired email.

Trigger: a verification link has expired without being used.
Clearly distinguishes link expiry from account deletion.
"""

from __future__ import annotations

from html import escape
from typing import Any

from ..base import detail_row, mask_email, paragraph
from ...registry import REGISTRY, TemplateDefinition
from ...shell import render_shell
from ...urls import EmailUrls
from ...icons import icon_for
from ...brand import SENDER_SUPPORT

TEMPLATE_ID = "A04"
TRIGGER_EVENT = "user.email_verification_expired"

TEMPLATE = TemplateDefinition(
    template_id=TEMPLATE_ID,
    name="Verification link expired",
    category="authentication",
    version="1.0.0",
    trigger_event=TRIGGER_EVENT,
    recipient_type="account_owner",
    sender="verify@pesaguard.co.ke",
    reply_to=SENDER_SUPPORT,
    subject_template="Your PesaGuard verification link has expired",
    preheader_template="Request a new verification link for {account_email}",
    required_variables=("account_email", "new_verification_url", "expired_at"),
    optional_variables=("display_name",),
    cta={"label": "Request a new link", "url_key": "new_verification_url"},
    sensitivity="confidential",
    preference_classification="essential",
    idempotency_strategy="event_idempotency_key",
    icon_category="email_verification",
)


def _render(context: dict[str, Any], urls: EmailUrls):
    account_email = context.get("account_email", "")
    display_name = context.get("display_name") or account_email
    new_verification_url = context.get("new_verification_url") or urls.verify_email(
        str(context.get("verification_token", ""))
    )
    expired_at = context.get("expired_at", "not specified")

    body = (
        paragraph(
            "Hello " + escape(str(display_name)) + ", your PesaGuard email "
            "verification link has expired."
        )
        + '<table width="100%">'
        + detail_row("Email address", mask_email(account_email))
        + detail_row("Link expired at", str(expired_at))
        + "</table>"
    )
    body += paragraph(
        "Expiring a verification link does not delete your account. Your account "
        "still exists but remains unverified until you confirm the address."
    )
    body += paragraph(
        "If you did not request a verification link, no action is needed and no "
        "account was activated."
    )

    plain = (
        f"Hello {escape(str(display_name))}, your PesaGuard email verification "
        f"link has expired.\n\n"
        f"Email address: {account_email}\n"
        f"Link expired at: {expired_at}\n"
        f"Request a new link: {new_verification_url}\n\n"
        "Expiring a verification link does not delete your account. If you did "
        "not request a verification link, no action is needed."
    )

    html, plain_text = render_shell(
        preheader=f"Request a new verification link for {account_email}",
        subject="Your PesaGuard verification link has expired",
        category="Email verification",
        headline="Verification link expired",
        body_html=body,
        icon=icon_for("email_verification"),
        cta_label="Request a new link",
        cta_url=new_verification_url,
        notice_html=(
            "Link expiry is not account deletion. Your account remains intact but "
            "unverified."
        ),
        notice_kind="info",
        plain_text=plain,
    )
    return (
        html,
        plain_text,
        "Request a new link",
        new_verification_url,
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
