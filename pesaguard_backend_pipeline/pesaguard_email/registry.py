"""
PesaGuard email template registry.

Every transactional email template is registered here with a stable template ID,
metadata, trigger event, recipient rules, sender identity, and an HTML renderer.
Templates declare required/optional variables which are validated before
rendering so critical information is never silently replaced with invented values.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Callable

from .icons import EmailIcon, icon_for

# Renderer signature: (context: dict) -> (html_body: str, plain_text: str | None,
#                                             cta_label: str | None,
#                                             cta_url: str | None,
#                                             notice: str | None,
#                                             notice_kind: str,
#                                             support_html: str | None)
TemplateRenderer = Callable[[dict[str, Any]], tuple[str, str | None, str | None, str | None, str | None, str, str | None]]


@dataclass(frozen=True)
class TemplateDefinition:
    template_id: str
    name: str
    category: str
    version: str
    trigger_event: str
    recipient_type: str  # "account_owner" | "tenant_admin" | "developer" | "operations" | "support"
    sender: str
    reply_to: str
    subject_template: str
    preheader_template: str
    required_variables: tuple[str, ...] = ()
    optional_variables: tuple[str, ...] = ()
    cta: dict[str, str] | None = None  # {"label": ..., "url_key": ...}
    secondary_cta: dict[str, str] | None = None
    sensitivity: str = "internal"  # "internal" | "confidential" | "restricted"
    preference_classification: str = "essential"  # "essential" | "operational" | "marketing"
    idempotency_strategy: str = "event_idempotency_key"
    icon_category: str = "administrative"
    test_fixture: dict[str, Any] = field(default_factory=dict)

    def icon(self) -> EmailIcon:
        return icon_for(self.icon_category)


@dataclass(frozen=True)
class RenderedTemplate:
    template_id: str
    subject: str
    preheader: str
    html: str
    plain_text: str
    sender: str
    reply_to: str
    icon: EmailIcon


class TemplateRegistry:
    """Central registry of all PesaGuard email templates."""

    def __init__(self) -> None:
        self._by_id: dict[str, TemplateDefinition] = {}
        self._by_event: dict[str, str] = {}  # trigger_event -> template_id

    def register(self, definition: TemplateDefinition) -> None:
        if definition.template_id in self._by_id:
            raise ValueError(f"template already registered: {definition.template_id}")
        if definition.trigger_event in self._by_event:
            raise ValueError(
                f"trigger event already mapped: {definition.trigger_event}"
            )
        self._by_id[definition.template_id] = definition
        self._by_event[definition.trigger_event] = definition.template_id

    def get(self, template_id: str) -> TemplateDefinition:
        try:
            return self._by_id[template_id]
        except KeyError as exc:
            raise KeyError(f"unknown template id: {template_id}") from exc

    def by_event(self, trigger_event: str) -> TemplateDefinition:
        template_id = self._by_event.get(trigger_event)
        if template_id is None:
            raise KeyError(f"no template registered for event: {trigger_event}")
        return self._by_id[template_id]

    def template_ids(self) -> list[str]:
        return sorted(self._by_id.keys())

    def events(self) -> list[str]:
        return sorted(self._by_event.keys())

    def __contains__(self, template_id: str) -> bool:
        return template_id in self._by_id

    def __len__(self) -> int:
        return len(self._by_id)


REGISTRY = TemplateRegistry()

__all__ = [
    "TemplateDefinition",
    "RenderedTemplate",
    "TemplateRegistry",
    "TemplateRenderer",
    "REGISTRY",
]
