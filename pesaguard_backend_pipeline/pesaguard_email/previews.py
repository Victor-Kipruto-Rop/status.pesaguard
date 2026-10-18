"""
Email template preview system.

Provides a developer-friendly way to inspect every registered template with
labelled test data, without sending real email. Intended for local development
and CI visual review. Preview routes must be secured and must not be publicly
accessible in production.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

import pesaguard_email.templates  # noqa: F401  (registers templates)
from pesaguard_email.dispatcher import _render_template
from pesaguard_email.registry import REGISTRY, RenderedTemplate
from pesaguard_email.urls import urls_from_env


@dataclass(frozen=True)
class TemplatePreview:
    template_id: str
    name: str
    category: str
    trigger_event: str
    subject: str
    preheader: str
    html: str
    plain_text: str
    sender: str
    reply_to: str
    required_variables: list[str]
    optional_variables: list[str]
    test_data_used: dict[str, Any]


def _default_test_data(definition) -> dict[str, Any]:
    data: dict[str, Any] = {
        "display_name": "John Mwangi",
        "account_email": "john.mwangi@example.com",
        "dashboard_url": "https://app.pesaguard.co.ke/",
        "created_at": "2026-01-01T00:00:00Z",
        "event_id": "evt_preview_0000000000000001",
        "correlation_id": "corr_preview_0000000000000001",
    }
    for var in definition.required_variables:
        if var in data:
            continue
        if var.endswith("_url"):
            data[var] = "https://app.pesaguard.co.ke/"
        elif var.endswith("_at") or var.endswith("_time") or var in (
            "expires_at", "locked_at", "unlocked_at", "changed_at",
            "completed_at", "requested_at", "resolved_at",
        ):
            data[var] = "2026-01-15T10:00:00Z"
        elif var in ("reference", "key_id", "project_id", "transaction_id"):
            data[var] = "ref_preview_0001"
        elif var in (
            "count", "records_processed", "matched_count", "unmatched_count",
            "exception_count",
        ):
            data[var] = 42
        elif var in ("period", "report_date"):
            data[var] = "January 2026"
        elif var == "environment":
            data[var] = "sandbox"
        elif var in ("severity", "risk_category"):
            data[var] = "medium"
        elif var == "entity":
            data[var] = "Merchant account #4421"
        elif var == "organization":
            data[var] = "Example SACCO Limited"
        elif var in ("outcome", "handling_status"):
            data[var] = "approved"
        elif var == "role":
            data[var] = "member"
        elif var == "inviter":
            data[var] = "admin@example.com"
        elif var == "workspace":
            data[var] = "Example Workspace"
        elif var in ("key_label", "key_id"):
            data[var] = "preview-key"
        elif var == "project":
            data[var] = "Example Project"
        elif var in ("old_email", "new_email", "current_email"):
            data[var] = "user@example.com"
        elif var == "mfa_method":
            data[var] = "authenticator app"
        elif var == "session_time":
            data[var] = "2026-01-15T03:00:00Z"
        elif var == "revoked_at":
            data[var] = "2026-01-15T04:00:00Z"
        elif var in (
            "review_url", "acceptance_url", "confirmation_url",
            "new_verification_url", "reset_url", "next_step_url",
            "security_url", "recovery_url", "report_url",
            "cancellation_url", "support_url", "login_url",
        ):
            data[var] = "https://app.pesaguard.co.ke/"
        elif var in ("session_description", "device_info", "location"):
            data[var] = "Chrome on Windows"
        elif var == "is_temporary":
            data[var] = True
        elif var == "all_sessions":
            data[var] = False
        elif var == "unlock_at":
            data[var] = "2026-01-16T10:00:00Z"
        elif var == "incident_reference":
            data[var] = "INC-2026-0001"
        elif var == "impacted_systems":
            data[var] = "API gateway, transaction worker"
        elif var == "opened_at":
            data[var] = "2026-01-15T09:00:00Z"
        elif var == "response_channel":
            data[var] = "#incidents on Slack"
        elif var == "incident_owner":
            data[var] = "ops-team@example.com"
        elif var == "status_page_url":
            data[var] = "https://status.pesaguard.co.ke"
        elif var == "challenge_type":
            data[var] = "push"
        elif var == "next_steps":
            data[var] = "Set up your M-Pesa integration."
        elif var == "tenant_name":
            data[var] = "Example SACCO"
        elif var in ("grace_period", "cancellation_url"):
            data[var] = "2026-01-22T00:00:00Z"
        elif var == "privacy_contact":
            data[var] = "privacy@pesaguard.co.ke"
        elif var == "retention_summary":
            data[var] = "90 days transaction data retained per policy"
        else:
            data[var] = "preview-value"
    return data


def list_previews() -> list[TemplatePreview]:
    """Build a preview for every registered template using test data.

    No email is sent. The test data is clearly labelled as fake.
    """
    urls = urls_from_env()
    previews: list[TemplatePreview] = []
    for tid in REGISTRY.template_ids():
        definition = REGISTRY.get(tid)
        test_data = _default_test_data(definition)
        rendered: RenderedTemplate = _render_template(definition, test_data, urls)
        previews.append(
            TemplatePreview(
                template_id=tid,
                name=definition.name,
                category=definition.category,
                trigger_event=definition.trigger_event,
                subject=rendered.subject,
                preheader=rendered.preheader,
                html=rendered.html,
                plain_text=rendered.plain_text,
                sender=rendered.sender,
                reply_to=rendered.reply_to,
                required_variables=list(definition.required_variables),
                optional_variables=list(definition.optional_variables),
                test_data_used=test_data,
            )
        )
    return previews


def preview_count() -> int:
    return len(REGISTRY.template_ids())


__all__ = ["TemplatePreview", "list_previews", "preview_count"]