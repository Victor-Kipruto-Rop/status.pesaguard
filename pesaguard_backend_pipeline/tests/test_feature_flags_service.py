from __future__ import annotations

import pytest

from feature_flags import FeatureFlagService
from tenant_settings_store import TenantSettingsStore


def test_feature_flags_respect_environment_and_defaults(monkeypatch):
    monkeypatch.delenv("PESAGUARD_FLAG_ALERTING", raising=False)
    monkeypatch.setenv("PESAGUARD_FLAG_AI_ASSISTANT", "true")
    service = FeatureFlagService()

    assert service.resolve("tenant-a", "alerting") is True
    assert service.resolve("tenant-a", "ai_assistant") is True
    assert service.resolve("tenant-a", "beta_dashboard") is False


def test_feature_flags_are_scoped_to_each_tenant_and_can_be_overridden(tmp_path):
    store = TenantSettingsStore(path=str(tmp_path / "tenant_settings_flags.json"))
    service = FeatureFlagService(store)
    assert service.resolve("tenant-a", "alerting") is True

    service.set_tenant_override("tenant-a", "alerting", False)
    assert service.resolve("tenant-a", "alerting") is False
    assert service.resolve("tenant-b", "alerting") is True

    service.clear_tenant_override("tenant-a", "alerting")
    assert service.resolve("tenant-a", "alerting") is True


def test_feature_flags_reject_unknown_or_malformed_values():
    service = FeatureFlagService()

    with pytest.raises(ValueError):
        service.resolve("tenant-a", "not_real")

    with pytest.raises(ValueError):
        service.set_tenant_override("tenant-a", "alerting", "maybe")

    with pytest.raises(ValueError):
        service.resolve("tenant-a", "")
