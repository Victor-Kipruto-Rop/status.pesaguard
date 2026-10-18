"""Tests for configurable per-tenant field mapping."""

from configurable_parser import ConfigurableParser
from tenant_settings_store import TenantSettingsStore
import tempfile
import os


def temp_store():
    fd, path = tempfile.mkstemp(suffix=".json")
    os.close(fd)
    store = TenantSettingsStore(path)
    return store, path


def test_map_fields_renames_source_fields():
    store, path = temp_store()
    try:
        parser = ConfigurableParser(store)
        parser.set_mapping(
            "tenant-a",
            "equity",
            {"amt": "amount", "acct": "account_id", "txn_ref": "transaction_id"},
        )
        payload = {
            "amt": "1250.00",
            "acct": "equity-acct-1",
            "txn_ref": "EQ-2026-001",
            "currency": "KES",
        }
        result = parser.map_fields("equity", payload, tenant_id="tenant-a")
        assert result["amount"] == "1250.00"
        assert result["account_id"] == "equity-acct-1"
        assert result["transaction_id"] == "EQ-2026-001"
        assert result["currency"] == "KES"
        assert "amt" not in result
        assert "acct" not in result
        assert "txn_ref" not in result
        # input not mutated
        assert "amt" in payload
    finally:
        os.unlink(path)


def test_map_fields_unknown_source_passthrough():
    store, path = temp_store()
    try:
        parser = ConfigurableParser(store)
        payload = {"amount": "10.00", "account_id": "acct-1"}
        result = parser.map_fields("unknown-source", payload, tenant_id="tenant-a")
        assert result == payload
    finally:
        os.unlink(path)


def test_map_fields_empty_mapping_passthrough():
    store, path = temp_store()
    try:
        parser = ConfigurableParser(store)
        payload = {"amount": "10.00"}
        result = parser.map_fields("equity", payload, tenant_id="tenant-a")
        assert result == payload
    finally:
        os.unlink(path)


def test_set_and_list_mapping():
    store, path = temp_store()
    try:
        parser = ConfigurableParser(store)
        parser.set_mapping(
            "tenant-a",
            "equity",
            {"amt": "amount"},
        )
        mappings = parser.list_mappings("tenant-a", "equity")
        assert mappings == {"amt": "amount"}
        all_mappings = parser.list_mappings("tenant-a")
        assert all_mappings == {"equity": {"amt": "amount"}}
    finally:
        os.unlink(path)


def test_remove_mapping():
    store, path = temp_store()
    try:
        parser = ConfigurableParser(store)
        parser.set_mapping(
            "tenant-a",
            "equity",
            {"amt": "amount", "acct": "account_id"},
        )
        parser.remove_mapping("tenant-a", "equity", "amt")
        mappings = parser.list_mappings("tenant-a", "equity")
        assert mappings == {"acct": "account_id"}
    finally:
        os.unlink(path)


def test_isolated_per_tenant():
    store, path = temp_store()
    try:
        parser = ConfigurableParser(store)
        parser.set_mapping("tenant-a", "equity", {"amt": "amount"})
        parser.set_mapping("tenant-b", "equity", {"amt": "transaction_amount"})
        assert parser.list_mappings("tenant-a", "equity") == {"amt": "amount"}
        assert parser.list_mappings("tenant-b", "equity") == {"amt": "transaction_amount"}
        # tenant-b's mapping should not affect tenant-a
        result_a = parser.map_fields("equity", {"amt": "10.00"}, tenant_id="tenant-a")
        assert result_a == {"amount": "10.00"}
        result_b = parser.map_fields("equity", {"amt": "10.00"}, tenant_id="tenant-b")
        assert result_b == {"transaction_amount": "10.00"}
    finally:
        os.unlink(path)