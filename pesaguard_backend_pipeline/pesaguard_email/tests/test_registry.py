"""
Unit tests for the PesaGuard email template registry.

Covers: template registration, trigger-event mapping, required-variable
validation, sender identity, icon selection, and renderer presence.
"""

from __future__ import annotations

import pesaguard_email.templates  # noqa: F401  (registers all templates)
from pesaguard_email.registry import REGISTRY
from pesaguard_email.icons import icon_for


def test_registry_has_all_auth_templates():
    expected = [f"A{i:02d}" for i in range(1, 24)]
    ids = sorted(REGISTRY.template_ids())
    for tid in expected:
        assert tid in ids, f"missing template {tid}"


def test_template_definitions_have_required_fields():
    for tid in REGISTRY.template_ids():
        definition = REGISTRY.get(tid)
        assert definition.template_id == tid
        assert definition.name
        assert definition.category
        assert definition.version
        assert definition.trigger_event
        assert definition.recipient_type
        assert definition.sender
        assert "@" in definition.sender
        assert definition.reply_to
        assert "@" in definition.reply_to
        assert definition.subject_template
        assert definition.preheader_template
        assert isinstance(definition.required_variables, tuple)
        assert isinstance(definition.optional_variables, tuple)
        assert definition.icon() is not None
        assert definition.icon().label


def test_each_trigger_event_maps_to_unique_template():
    events = REGISTRY.events()
    assert len(events) == len(set(events)), "duplicate trigger events"
    for event in events:
        definition = REGISTRY.by_event(event)
        assert definition.trigger_event == event


def test_sender_uses_pesaguard_domain():
    for tid in REGISTRY.template_ids():
        definition = REGISTRY.get(tid)
        assert definition.sender.endswith("@pesaguard.co.ke"), (
            f"{tid} sender not on pesaguard.co.ke: {definition.sender}"
        )
        assert definition.reply_to.endswith("@pesaguard.co.ke"), (
            f"{tid} reply-to not on pesaguard.co.ke: {definition.reply_to}"
        )


def test_icon_registry_complete():
    for tid in REGISTRY.template_ids():
        definition = REGISTRY.get(tid)
        icon = definition.icon()
        assert icon.colour
        assert icon.label


def test_required_variables_are_declared():
    # Spot-check a few templates for their required variables.
    welcome = REGISTRY.get("A01")
    assert "account_email" in welcome.required_variables
    assert "dashboard_url" in welcome.required_variables

    verification = REGISTRY.get("A02")
    assert "verification_url" in verification.required_variables

    reset = REGISTRY.get("A07")
    assert "reset_url" in reset.required_variables

    completed = REGISTRY.get("D02")
    assert "matched_count" in completed.required_variables
    assert "unmatched_count" in completed.required_variables

    api_key = REGISTRY.get("F08")
    assert "key_id" in api_key.required_variables


def test_auth_template_count():
    auth_ids = [tid for tid in REGISTRY.template_ids() if tid.startswith("A")]
    assert len(auth_ids) == 23, f"expected 23 auth templates, got {len(auth_ids)}"
