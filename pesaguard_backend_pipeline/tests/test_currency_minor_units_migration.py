from __future__ import annotations

import importlib.util
from pathlib import Path

import pytest
from alembic.migration import MigrationContext
from alembic.operations import Operations
from sqlalchemy import create_engine, inspect, text


MIGRATION_PATH = (
    Path(__file__).parents[1]
    / "alembic"
    / "versions"
    / "20261013_currency_minor_units.py"
)


def _load_migration():
    spec = importlib.util.spec_from_file_location("currency_minor_units", MIGRATION_PATH)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _legacy_schema(connection):
    connection.exec_driver_sql(
        """
        CREATE TABLE internal_records (
            id TEXT PRIMARY KEY,
            amount NUMERIC NOT NULL
        )
        """
    )
    connection.exec_driver_sql(
        """
        CREATE TABLE merchant_metrics (
            id TEXT PRIMARY KEY,
            tenant_id TEXT NOT NULL,
            merchant_id TEXT NOT NULL,
            granularity TEXT NOT NULL,
            bucket_start TEXT NOT NULL,
            total_volume NUMERIC NOT NULL,
            average_transaction NUMERIC NOT NULL,
            CONSTRAINT uq_merchant_metric_bucket
                UNIQUE (tenant_id, merchant_id, granularity, bucket_start)
        )
        """
    )
    connection.exec_driver_sql(
        """
        CREATE TABLE settlements (
            id TEXT PRIMARY KEY,
            tenant_id TEXT NOT NULL,
            settlement_key TEXT NOT NULL,
            currency VARCHAR(8) NOT NULL,
            CONSTRAINT uq_settlements_tenant_key UNIQUE (tenant_id, settlement_key),
            CONSTRAINT ck_settlements_currency_nonempty CHECK (currency <> '')
        )
        """
    )


def _run_migration(connection, name):
    context = MigrationContext.configure(connection)
    with Operations.context(context):
        getattr(_load_migration(), name)()


def test_upgrade_separates_metric_identity_by_currency_and_downgrade_guards_collision():
    engine = create_engine("sqlite:///:memory:")
    with engine.begin() as connection:
        _legacy_schema(connection)
        _run_migration(connection, "upgrade")

        connection.execute(
            text(
                """
                INSERT INTO merchant_metrics (
                    id, tenant_id, merchant_id, granularity, bucket_start,
                    currency, total_volume, average_transaction
                ) VALUES
                    ('kes', 'tenant-a', 'merchant-a', 'day', '2026-10-13', 'KES', 10, 10),
                    ('usd', 'tenant-a', 'merchant-a', 'day', '2026-10-13', 'USD', 10, 10)
                """
            )
        )
        rows = connection.execute(
            text("SELECT currency FROM merchant_metrics ORDER BY currency")
        ).scalars().all()
        assert rows == ["KES", "USD"]

        with pytest.raises(RuntimeError, match="multiple currencies share a metric bucket"):
            _run_migration(connection, "downgrade")


def test_downgrade_refuses_to_drop_non_kes_metric_currency():
    engine = create_engine("sqlite:///:memory:")
    with engine.begin() as connection:
        _legacy_schema(connection)
        _run_migration(connection, "upgrade")
        connection.execute(
            text(
                """
                INSERT INTO merchant_metrics (
                    id, tenant_id, merchant_id, granularity, bucket_start,
                    currency, total_volume, average_transaction
                ) VALUES ('usd', 'tenant-a', 'merchant-a', 'day', '2026-10-13', 'USD', 10, 10)
                """
            )
        )

        with pytest.raises(RuntimeError, match="non-KES buckets"):
            _run_migration(connection, "downgrade")


def test_downgrade_round_trips_legacy_kes_only_schema():
    engine = create_engine("sqlite:///:memory:")
    with engine.begin() as connection:
        _legacy_schema(connection)
        _run_migration(connection, "upgrade")
        connection.execute(
            text(
                """
                INSERT INTO merchant_metrics (
                    id, tenant_id, merchant_id, granularity, bucket_start,
                    currency, total_volume, average_transaction
                ) VALUES ('kes', 'tenant-a', 'merchant-a', 'day', '2026-10-13', 'KES', 10, 10)
                """
            )
        )

        _run_migration(connection, "downgrade")

        assert "currency" not in {
            column["name"] for column in inspect(connection).get_columns("merchant_metrics")
        }
        assert "currency" not in {
            column["name"] for column in inspect(connection).get_columns("internal_records")
        }
