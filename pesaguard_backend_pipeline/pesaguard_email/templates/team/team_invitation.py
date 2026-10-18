"""
G01 — Team invitation sent email.

Trigger: team invitation sent. Includes role, expiry, and secure acceptance link.
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

TEMPLATE_ID = "G01"
TRIGGER_EVENT = "team.invitation_sent"

TEMPLATE = TemplateDefinition(
    template_id=TEMPLATE_ID,
    name="Team invitation sent",
    category="team",
    version="1.0.0",
    trigger_event=TRIGGER_EVENT,
    recipient_type="team_member",
    sender=SENDER_NO_REPLY,
    reply_to=SENDER_SUPPORT,
    subject_template="You are invited to join {workspace} on PesaGuard",
    preheader_template="{inviter} invited you to {workspace} as a {role}",
    required_variables=("workspace", "inviter", "role", "acceptance_url",
                        "expires_at"),
    optional_variables=("display_name",),
    cta={"label": "Accept invitation", "url_key": "acceptance_url"},
    sensitivity="internal",
    preference_classification="essential",
    idempotency_strategy="event_idempotency_key",
    icon_category="team_invitation",
)


def _render(context: dict[str, Any], urls: EmailUrls):
    workspace = context.get("workspace", "")
    inviter = context.get("inviter", "")
    role = context.get("role", "")
    acceptance_url = context.get("acceptance_url")
    expires_at = context.get("expires_at", "not specified")
    display_name = context.get("display_name")

    body = (
        paragraph(
            "Hello" + (", " + escape(str(display_name)) if display_name else "") +
            ", you have been invited to join " + escape(str(workspace)) +
            " on PesaGuard."
        )
        + '<table width="100%">'
        + detail_row("Workspace", escape(str(workspace)))
        + detail_row("Invited by", escape(str(inviter)))
        + detail_row("Role", escape(str(role)))
        + detail_row("Invitation expires", str(expires_at))
        + "</table>"
    )
    body += paragraph(
        "Accept the invitation to access the workspace. The invitation expires "
        "after the time shown."
    )

    plain = (
        f"Hello{', ' + escape(str(display_name)) if display_name else ''}, you "
        f"have been invited to join {workspace} on PesaGuard.\n\n"
        f"Workspace: {workspace}\n"
        f"Invited by: {inviter}\n"
        f"Role: {role}\n"
        f"Invitation expires: {expires_at}\n"
    )
    if acceptance_url:
        plain += f"Accept: {acceptance_url}\n"
    plain += (
        "Accept the invitation to access the workspace. The invitation expires "
        "after the time shown."
    )

    html, plain_text = render_shell(
        preheader=f"{inviter} invited you to {workspace} as a {role}",
        subject=f"You are invited to join {workspace} on PesaGuard",
        category="Team invitation",
        headline="Team invitation",
        body_html=body,
        icon=icon_for("team_invitation"),
        cta_label="Accept invitation",
        cta_url=acceptance_url,
        notice_html=(
            "The invitation expires after the time shown. Request a new "
            "invitation if it expires before you accept."
        ),
        notice_kind="info",
        plain_text=plain,
    )
    return (
        html,
        plain_text,
        "Accept invitation",
        acceptance_url,
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
