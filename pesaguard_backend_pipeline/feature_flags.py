"""Tenant-scoped feature-flag service used for safe production rollouts.

The service is intentionally small but production-oriented: it keeps feature
names explicit, enforces validation, isolates overrides by tenant, and honors
environment overrides when operators need a quick kill switch without redeploying.
"""
from __future__ import annotations

import os
import re
import threading
from copy import deepcopy
from typing import Any, Dict, Mapping, MutableMapping, Optional

from tenant_settings_store import TenantSettingsStore

DEFAULT_FEATURE_FLAGS: Dict[str, bool] = {
    "alerting": True,
    "ai_assistant": False,
    "beta_dashboard": False,
    "email_templates": True,
    "mpesa_connectors": True,
    "reconciliation_ai": False,
    "self_service_tenant_admin": True,
    "webhook_retry": True,
}

_ENV_FLAG_PREFIX = "PESAGUARD_FLAG_"


def _normalize_flag_name(flag_name: str) -> str:
    if not isinstance(flag_name, str):
        raise ValueError("feature flag name must be a string")
    normalized = re.sub(r"[^a-z0-9]+", "_", flag_name.strip().lower())
    normalized = normalized.strip("_")
    if not normalized:
        raise ValueError("feature flag name must not be empty")
    if len(normalized) > 64:
        raise ValueError("feature flag name is too long")
    return normalized


def _parse_bool(value: Any, *, flag_name: str) -> bool:
    if isinstance(value, bool):
        return value
    if isinstance(value, int) and value in {0, 1}:
        return bool(value)
    if isinstance(value, str):
        normalized = value.strip().lower()
        if normalized in {"1", "true", "yes", "on"}:
            return True
        if normalized in {"0", "false", "no", "off"}:
            return False
    raise ValueError(f"feature flag '{flag_name}' must be a boolean-like value")


class FeatureFlagService:
    """Central feature-flag policy with tenant and environment overrides."""

    def __init__(self, tenant_store: Optional[MutableMapping[str, Any]] = None) -> None:
        self._tenant_store: Any = tenant_store
        if self._tenant_store is None:
            self._tenant_store = TenantSettingsStore()
        self._global_overrides: Dict[str, bool] = {}
        self._tenant_update_lock = threading.RLock()

    def register_override(self, flag_name: str, enabled: bool) -> None:
        normalized = _normalize_flag_name(flag_name)
        if normalized not in DEFAULT_FEATURE_FLAGS:
            raise ValueError(f"unknown feature flag '{normalized}'")
        if not isinstance(enabled, bool):
            raise ValueError(f"feature flag '{normalized}' override must be a boolean")
        self._global_overrides[normalized] = enabled

    def clear_override(self, flag_name: str) -> None:
        normalized = _normalize_flag_name(flag_name)
        self._global_overrides.pop(normalized, None)

    def _tenant_feature_flags(self, tenant_id: str) -> Dict[str, Any]:
        tenant_key = str(tenant_id or "default")
        settings = self._tenant_store.get(tenant_key, {}) if hasattr(self._tenant_store, "get") else {}
        if not isinstance(settings, Mapping):
            return {}
        feature_flags = settings.get("feature_flags")
        if not isinstance(feature_flags, Mapping):
            return {}
        return dict(feature_flags)

    def _environment_value(self, flag_name: str) -> Optional[bool]:
        env_name = f"{_ENV_FLAG_PREFIX}{flag_name.upper()}"
        raw = os.getenv(env_name)
        if raw is None:
            return None
        return _parse_bool(raw, flag_name=flag_name)

    def resolve(self, tenant_id: str, flag_name: str) -> bool:
        tenant_key = self._validate_tenant_id(tenant_id)
        normalized = _normalize_flag_name(flag_name)
        if normalized not in DEFAULT_FEATURE_FLAGS:
            raise ValueError(f"unknown feature flag '{normalized}'")

        env_value = self._environment_value(normalized)
        if env_value is not None:
            return env_value

        if normalized in self._global_overrides:
            return self._global_overrides[normalized]

        tenant_flags = self._tenant_feature_flags(tenant_key)
        tenant_value = tenant_flags.get(normalized)
        if tenant_value is not None:
            return _parse_bool(tenant_value, flag_name=normalized)

        return DEFAULT_FEATURE_FLAGS[normalized]

    def set_tenant_override(self, tenant_id: str, flag_name: str, enabled: bool) -> Dict[str, bool]:
        tenant_key = self._validate_tenant_id(tenant_id)
        normalized = _normalize_flag_name(flag_name)
        if normalized not in DEFAULT_FEATURE_FLAGS:
            raise ValueError(f"unknown feature flag '{normalized}'")
        if not isinstance(enabled, bool):
            raise ValueError(f"feature flag '{normalized}' override must be a boolean")

        with self._tenant_update_lock:
            tenant_settings = self._tenant_store.get(tenant_key, {}) if hasattr(self._tenant_store, "get") else {}
            if not isinstance(tenant_settings, dict):
                tenant_settings = {}

            feature_flags = dict(tenant_settings.get("feature_flags") or {})
            feature_flags[normalized] = enabled
            self._tenant_store.update(tenant_key, {"feature_flags": feature_flags})
            return deepcopy(feature_flags)

    def clear_tenant_override(self, tenant_id: str, flag_name: str) -> Dict[str, bool]:
        tenant_key = self._validate_tenant_id(tenant_id)
        normalized = _normalize_flag_name(flag_name)
        if normalized not in DEFAULT_FEATURE_FLAGS:
            raise ValueError(f"unknown feature flag '{normalized}'")
        with self._tenant_update_lock:
            tenant_settings = self._tenant_store.get(tenant_key, {}) if hasattr(self._tenant_store, "get") else {}
            if not isinstance(tenant_settings, dict):
                tenant_settings = {}

            feature_flags = dict(tenant_settings.get("feature_flags") or {})
            feature_flags.pop(normalized, None)
            replace_section = getattr(self._tenant_store, "replace_section", None)
            if callable(replace_section):
                replace_section(tenant_key, "feature_flags", feature_flags)
            else:
                self._tenant_store.update(tenant_key, {"feature_flags": feature_flags})
            return deepcopy(feature_flags)

    def snapshot(self, tenant_id: Optional[str] = None) -> Dict[str, bool]:
        tenant_id = str(tenant_id or "default")
        return {
            flag_name: self.resolve(tenant_id, flag_name)
            for flag_name in sorted(DEFAULT_FEATURE_FLAGS)
        }

    @staticmethod
    def _validate_tenant_id(tenant_id: str) -> str:
        if not isinstance(tenant_id, str) or not tenant_id.strip():
            raise ValueError("tenant_id is required")
        return tenant_id.strip()


feature_flag_service = FeatureFlagService()


def is_enabled(flag_name: str, *, tenant_id: str = "default") -> bool:
    return feature_flag_service.resolve(tenant_id, flag_name)


def snapshot(tenant_id: Optional[str] = None) -> Dict[str, bool]:
    return feature_flag_service.snapshot(tenant_id)
