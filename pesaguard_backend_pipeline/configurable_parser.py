"""Configurable per-tenant field mapping for custom import sources.

Allows tenants to rename source fields before they reach the adapter
normalization layer. Mappings are stored in the existing TenantSettingsStore
under a ``field_mappings`` key, scoped by tenant and source/provider.

Example tenant settings entry::

    {
      "field_mappings": {
        "equity": {
          "amt": "amount",
          "acct": "account_id",
          "txn_ref": "transaction_id",
          "posted_at": "timestamp"
        }
      }
    }

Usage:

    parser = ConfigurableParser(tenant_settings_store)
    mapped = parser.map_fields("equity", payload, tenant_id="tenant-a")
    envelope = equity_adapter.normalize(mapped, tenant_id="tenant-a")
"""

from __future__ import annotations

from typing import Any, Dict, Mapping, Optional

from tenant_settings_store import TenantSettingsStore


FIELD_MAPPINGS_SECTION = "field_mappings"


class ConfigurableParser:
    """Applies per-tenant, per-source field name mappings to raw payloads."""

    def __init__(self, settings_store: Optional[TenantSettingsStore] = None):
        self.settings_store = settings_store or TenantSettingsStore()

    def _mappings_for(
        self, tenant_id: str, source: str
    ) -> Dict[str, str]:
        tenant_id = str(tenant_id or "default").strip()
        source = str(source or "default").strip().lower()
        settings = self.settings_store.get(tenant_id, {})
        mappings = settings.get(FIELD_MAPPINGS_SECTION, {})
        if not isinstance(mappings, dict):
            return {}
        source_mappings = mappings.get(source, {})
        if not isinstance(source_mappings, dict):
            return {}
        return {str(k): str(v) for k, v in source_mappings.items()}

    def map_fields(
        self,
        source: str,
        payload: Mapping[str, Any],
        *,
        tenant_id: str = "default",
    ) -> Dict[str, Any]:
        """Return a copy of ``payload`` with source fields renamed per tenant mapping.

        Only fields present in both the payload and the mapping are renamed.
        Unmapped fields are preserved unchanged. Returns a new dict; the input
        is never mutated.
        """
        if not isinstance(payload, Mapping):
            return dict(payload)
        mappings = self._mappings_for(tenant_id, source)
        if not mappings:
            return dict(payload)
        result: Dict[str, Any] = dict(payload)
        for src_field, canonical_field in mappings.items():
            if src_field in result and src_field != canonical_field:
                result[canonical_field] = result.pop(src_field)
        return result

    def set_mapping(
        self,
        tenant_id: str,
        source: str,
        field_mapping: Mapping[str, str],
    ) -> Dict[str, Any]:
        """Persist a field mapping for a tenant/source. Returns merged settings."""
        tenant_id = str(tenant_id or "default").strip()
        source = str(source or "default").strip().lower()
        settings = self.settings_store.get(tenant_id, {})
        mappings = settings.get(FIELD_MAPPINGS_SECTION, {})
        if not isinstance(mappings, dict):
            mappings = {}
        source_mappings = mappings.get(source, {})
        if not isinstance(source_mappings, dict):
            source_mappings = {}
        source_mappings.update({str(k): str(v) for k, v in field_mapping.items()})
        mappings[source] = source_mappings
        return self.settings_store.replace_section(
            tenant_id, FIELD_MAPPINGS_SECTION, mappings
        )

    def remove_mapping(
        self,
        tenant_id: str,
        source: str,
        src_field: str,
    ) -> Dict[str, Any]:
        """Remove a single field mapping for a tenant/source."""
        tenant_id = str(tenant_id or "default").strip()
        source = str(source or "default").strip().lower()
        settings = self.settings_store.get(tenant_id, {})
        mappings = settings.get(FIELD_MAPPINGS_SECTION, {})
        if not isinstance(mappings, dict):
            return settings
        source_mappings = mappings.get(source, {})
        if isinstance(source_mappings, dict):
            source_mappings.pop(str(src_field), None)
            mappings[source] = source_mappings
        return self.settings_store.replace_section(
            tenant_id, FIELD_MAPPINGS_SECTION, mappings
        )

    def list_mappings(
        self, tenant_id: str, source: Optional[str] = None
    ) -> Dict[str, Any]:
        """Return all field mappings for a tenant, optionally scoped to one source."""
        tenant_id = str(tenant_id or "default").strip()
        settings = self.settings_store.get(tenant_id, {})
        mappings = settings.get(FIELD_MAPPINGS_SECTION, {})
        if not isinstance(mappings, dict):
            return {}
        if source is None:
            return dict(mappings)
        source = str(source).strip().lower()
        return dict(mappings.get(source, {}))