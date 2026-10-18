"""Opt-in live verification for bank-partner adapters (Equity, KCB, Absa).

Runs REAL bank statement exports through the full connector -> schema
validation -> adapter normalization -> event-store persistence path. Tests
are skipped unless the corresponding environment variable points at a real
export file produced by the bank (portal export, API dump, or statement file):

    PESAGUARD_LIVE_EQUITY_STATEMENT=C:\\statements\\equity-2026-10-18.json
    PESAGUARD_LIVE_KCB_STATEMENT=C:\\statements\\kcb-2026-10-18.csv
    PESAGUARD_LIVE_ABSA_STATEMENT=C:\\statements\\absa-2026-10-18.json

Supported file formats: JSON (one object or a list of objects) or CSV with a
header row. If the export uses non-canonical column names, provide a per-bank
rename map (JSON object of source -> canonical field names):

    PESAGUARD_LIVE_EQUITY_FIELD_MAP={"amt":"amount","acct":"account_id","ref":"transaction_id"}

Optional controls:

    PESAGUARD_LIVE_TENANT_ID   tenant scope for the smoke run (default "live-smoke")
    PESAGUARD_LIVE_MAX_RECORDS cap on records ingested per run (default 25)

Skipped by default so unit runs never depend on real bank data. The harness
itself is covered by always-on tests, including an end-to-end self-test that
exercises loader, field mapping, connector, and persistence mechanics.
"""

from __future__ import annotations

import csv
import json
import os
import uuid
from decimal import Decimal
from pathlib import Path

import pytest

from configurable_parser import ConfigurableParser
from connectors.registry import get_connector
from event_store import EventStore, ProcessResult
from ingestion import IngestionError, IngestionService
from tenant_settings_store import TenantSettingsStore

BANKS = ("equity", "kcb", "absa")


def statement_env(bank: str) -> str:
    return f"PESAGUARD_LIVE_{bank.upper()}_STATEMENT"


def field_map_env(bank: str) -> str:
    return f"PESAGUARD_LIVE_{bank.upper()}_FIELD_MAP"


def load_records(path: str | Path) -> list[dict]:
    """Load statement records from a real export file (JSON or CSV)."""
    file_path = Path(path)
    if not file_path.is_file():
        raise FileNotFoundError(f"statement file not found: {file_path}")
    suffix = file_path.suffix.lower()
    if suffix == ".json":
        payload = json.loads(file_path.read_text(encoding="utf-8-sig"))
        records = payload if isinstance(payload, list) else [payload]
    elif suffix == ".csv":
        with file_path.open("r", encoding="utf-8-sig", newline="") as handle:
            records = [dict(row) for row in csv.DictReader(handle)]
    else:
        raise ValueError(
            f"unsupported statement format {suffix or '<none>'!r}; use .json or .csv"
        )
    if not all(isinstance(record, dict) for record in records):
        raise ValueError("statement records must be JSON objects")
    return records


def field_mapping_for(bank: str) -> dict[str, str]:
    """Parse and validate the optional per-bank canonical field rename map."""
    raw = os.getenv(field_map_env(bank), "").strip()
    if not raw:
        return {}
    try:
        mapping = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise ValueError(f"{field_map_env(bank)} must be valid JSON") from exc
    if not isinstance(mapping, dict) or not mapping:
        raise ValueError(f"{field_map_env(bank)} must be a non-empty JSON object")
    if not all(isinstance(key, str) and isinstance(value, str) for key, value in mapping.items()):
        raise ValueError(f"{field_map_env(bank)} must map source field names to canonical names")
    return dict(mapping)


def run_live_smoke(bank: str, settings_dir: Path) -> list:
    """Ingest up to MAX_RECORDS records of a real statement through the pipeline."""
    statement = os.environ[statement_env(bank)]
    tenant_id = (os.getenv("PESAGUARD_LIVE_TENANT_ID") or "live-smoke").strip()
    if not tenant_id:
        raise ValueError("PESAGUARD_LIVE_TENANT_ID must not be empty")
    limit = max(1, int(os.getenv("PESAGUARD_LIVE_MAX_RECORDS", "25")))
    records = load_records(statement)
    parser = ConfigurableParser(
        TenantSettingsStore(str(settings_dir / f"{bank}-settings.json"))
    )
    mapping = field_mapping_for(bank)
    if mapping:
        parser.set_mapping(tenant_id, bank, mapping)
    store = EventStore()
    service = IngestionService(store)
    connector = get_connector(bank, service)
    results: list = []
    try:
        for index, record in enumerate(records[:limit], start=1):
            mapped = parser.map_fields(bank, record, tenant_id=tenant_id)
            try:
                results.extend(connector.ingest(mapped, tenant_id=tenant_id))
            except IngestionError as exc:
                raise AssertionError(
                    f"{bank} statement record {index} was rejected by the ingestion boundary: {exc}"
                ) from exc
    finally:
        engine = getattr(store, "engine", None)
        if engine is not None:
            engine.dispose()
    return results


@pytest.mark.integration
@pytest.mark.parametrize("bank", BANKS)
def test_live_statement_ingestion_smoke(bank: str, tmp_path: Path):
    if not os.getenv(statement_env(bank), "").strip():
        pytest.skip(
            f"set {statement_env(bank)} to a real statement export (.json or .csv) "
            f"to verify {bank} ingestion against live bank data"
        )
    results = run_live_smoke(bank, tmp_path)
    assert results, "statement did not yield any ingestible records"
    tenant_id = (os.getenv("PESAGUARD_LIVE_TENANT_ID") or "live-smoke").strip()
    for result in results:
        assert result.result in (ProcessResult.STORED, ProcessResult.DUPLICATE)
        canonical = result.envelope.canonical
        assert canonical.provider == bank
        assert canonical.tenant_id == tenant_id
        assert Decimal(canonical.amount) > 0
        assert len(canonical.currency) == 3
        assert canonical.transaction_time


def test_harness_ingests_a_configured_statement_end_to_end(tmp_path: Path, monkeypatch):
    """Self-test: the live harness mechanics work without real bank data."""
    statement = tmp_path / "equity-selftest.json"
    statement.write_text(
        json.dumps(
            [
                {
                    "transaction_id": f"EQ-SELF-{uuid.uuid4().hex[:10]}",
                    "amount": "250.00",
                    "account_id": "equity-selftest",
                    "currency": "KES",
                    "timestamp": "2026-10-18T09:00:00Z",
                    "sort_code": "680123",
                    "narration": "harness self-test",
                },
                {
                    "txn_ref": f"EQ-SELF-MAPPED-{uuid.uuid4().hex[:10]}",
                    "amt": "175.50",
                    "acct": "equity-selftest",
                    "currency": "KES",
                    "timestamp": "2026-10-18T09:05:00Z",
                },
            ]
        ),
        encoding="utf-8",
    )
    monkeypatch.setenv(statement_env("equity"), str(statement))
    monkeypatch.setenv("PESAGUARD_LIVE_TENANT_ID", "harness-self-test")
    monkeypatch.setenv(
        field_map_env("equity"),
        json.dumps({"txn_ref": "transaction_id", "amt": "amount", "acct": "account_id"}),
    )

    results = run_live_smoke("equity", tmp_path)

    assert len(results) == 2
    for result in results:
        assert result.result in (ProcessResult.STORED, ProcessResult.DUPLICATE)
        assert result.envelope.canonical.provider == "equity"
        assert Decimal(result.envelope.canonical.amount) > 0
    mapped = [
        result
        for result in results
        if result.envelope.canonical.provider_transaction_id.startswith("EQ-SELF-MAPPED")
    ]
    assert mapped and mapped[0].envelope.canonical.amount == "175.50"


def test_load_records_json_list_and_single_object(tmp_path: Path):
    many = tmp_path / "many.json"
    many.write_text(json.dumps([{"transaction_id": "a"}, {"transaction_id": "b"}]), encoding="utf-8")
    assert load_records(many) == [{"transaction_id": "a"}, {"transaction_id": "b"}]
    single = tmp_path / "single.json"
    single.write_text(json.dumps({"transaction_id": "c"}), encoding="utf-8")
    assert load_records(single) == [{"transaction_id": "c"}]


def test_load_records_csv_uses_header_row(tmp_path: Path):
    statement = tmp_path / "statement.csv"
    statement.write_text(
        "transaction_id,amount,account_id\nEQ-CSV-1,10.00,acct-1\nEQ-CSV-2,20.50,acct-1\n",
        encoding="utf-8",
    )
    records = load_records(statement)
    assert records == [
        {"transaction_id": "EQ-CSV-1", "amount": "10.00", "account_id": "acct-1"},
        {"transaction_id": "EQ-CSV-2", "amount": "20.50", "account_id": "acct-1"},
    ]


def test_load_records_rejects_missing_and_unsupported_files(tmp_path: Path):
    with pytest.raises(FileNotFoundError):
        load_records(tmp_path / "missing.json")
    unsupported = tmp_path / "statement.xlsx"
    unsupported.write_bytes(b"fake")
    with pytest.raises(ValueError, match="unsupported statement format"):
        load_records(unsupported)
    malformed = tmp_path / "malformed.json"
    malformed.write_text("[1, 2]", encoding="utf-8")
    with pytest.raises(ValueError, match="must be JSON objects"):
        load_records(malformed)


def test_field_mapping_env_is_parsed_and_validated(monkeypatch):
    assert field_mapping_for("equity") == {}
    monkeypatch.setenv(field_map_env("kcb"), json.dumps({"ref": "transaction_id"}))
    assert field_mapping_for("kcb") == {"ref": "transaction_id"}
    monkeypatch.setenv(field_map_env("kcb"), "{not-json")
    with pytest.raises(ValueError, match="must be valid JSON"):
        field_mapping_for("kcb")
    monkeypatch.setenv(field_map_env("kcb"), json.dumps(["amt", "amount"]))
    with pytest.raises(ValueError, match="JSON object"):
        field_mapping_for("kcb")
    monkeypatch.setenv(field_map_env("kcb"), json.dumps({"amt": 123}))
    with pytest.raises(ValueError, match="canonical names"):
        field_mapping_for("kcb")