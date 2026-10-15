from __future__ import annotations

import importlib.util
from pathlib import Path

import pytest
from alembic.migration import MigrationContext
from alembic.operations import Operations
from sqlalchemy import create_engine, text
from sqlalchemy.exc import IntegrityError


MIGRATION_PATH = (
    Path(__file__).parents[1]
    / "alembic"
    / "versions"
    / "20261013_harden_transaction_ingestion.py"
)


def _load_migration():
    spec = importlib.util.spec_from_file_location("harden_transaction_ingestion", MIGRATION_PATH)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_migration_enforces_database_immutability_and_allows_privacy_redaction():
    engine = create_engine("sqlite:///:memory:")
    with engine.begin() as connection:
        connection.exec_driver_sql(
            """
            CREATE TABLE transactions (
                id TEXT PRIMARY KEY,
                raw_payload JSON NOT NULL,
                trans_id TEXT NOT NULL,
                tenant_id TEXT NOT NULL,
                provider_account_id TEXT NOT NULL,
                provider TEXT NOT NULL,
                idempotency_key TEXT NOT NULL,
                external_reference TEXT,
                provider_transaction_id TEXT NOT NULL,
                trans_amount NUMERIC NOT NULL,
                currency TEXT NOT NULL,
                msisdn TEXT NOT NULL,
                business_short_code TEXT NOT NULL,
                trans_time TEXT NOT NULL
            )
            """
        )
        connection.exec_driver_sql(
            """
            CREATE TABLE import_jobs (
                id TEXT PRIMARY KEY,
                tenant_id TEXT NOT NULL,
                records_received INTEGER NOT NULL DEFAULT 0,
                records_valid INTEGER NOT NULL DEFAULT 0,
                records_failed INTEGER NOT NULL DEFAULT 0
            )
            """
        )
        migration_context = MigrationContext.configure(connection)
        with Operations.context(migration_context):
            _load_migration().upgrade()

        connection.execute(
            text(
                """
                INSERT INTO transactions (
                    id, raw_payload, trans_id, tenant_id, provider_account_id,
                    provider, idempotency_key, provider_transaction_id,
                    trans_amount, currency, msisdn, business_short_code, trans_time
                ) VALUES (
                    'txn-1', '{"TransID":"txn-1"}', 'txn-1', 'tenant-a', '600000',
                    'mpesa', 'transid:txn-1', 'txn-1', 10.00, 'KES',
                    'tok:v1:abc', '600000', '20261013120000'
                )
                """
            )
        )
        row = connection.execute(
            text("SELECT raw_payload_hash, source_schema_version FROM transactions WHERE id='txn-1'")
        ).one()
        assert row.raw_payload_hash is None
        assert row.source_schema_version == "1.0"

        with pytest.raises(IntegrityError, match="transaction source data is immutable"):
            connection.execute(
                text("UPDATE transactions SET raw_payload='{\"TransID\":\"changed\"}' WHERE id='txn-1'")
            )

        connection.execute(
            text(
                "UPDATE transactions SET raw_payload=:payload, msisdn='redacted:token' WHERE id='txn-1'"
            ),
            {"payload": '{"redacted":true,"payload_hash":"abc"}'},
        )
        assert connection.execute(
            text("SELECT json_extract(raw_payload, '$.redacted') FROM transactions WHERE id='txn-1'")
        ).scalar_one() == 1
        with pytest.raises(IntegrityError, match="transaction source data is immutable"):
            connection.execute(
                text("UPDATE transactions SET trans_amount=99.00 WHERE id='txn-1'")
            )
    engine.dispose()
