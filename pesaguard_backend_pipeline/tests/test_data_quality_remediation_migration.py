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
    / "20261014_quarantine_remediation_history.py"
)


def _load_migration():
    spec = importlib.util.spec_from_file_location(
        "quarantine_remediation_history", MIGRATION_PATH
    )
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_migration_creates_append_only_tenant_history_and_reverses_cleanly():
    engine = create_engine("sqlite:///:memory:")
    with engine.begin() as connection:
        connection.exec_driver_sql(
            "PRAGMA foreign_keys=ON"
        )
        connection.exec_driver_sql(
            "CREATE TABLE quarantine_records ("
            "id TEXT PRIMARY KEY, tenant_id TEXT NOT NULL, provider_id TEXT NOT NULL)"
        )
        connection.exec_driver_sql(
            "CREATE TABLE transaction_provenances (id TEXT PRIMARY KEY)"
        )
        connection.exec_driver_sql(
            "CREATE TABLE transactions ("
            "id TEXT PRIMARY KEY, tenant_id TEXT, provider TEXT, "
            "currency TEXT, created_at DATETIME)"
        )
        migration_context = MigrationContext.configure(connection)
        with Operations.context(migration_context):
            migration = _load_migration()
            migration.upgrade()

        connection.execute(
            text(
                "INSERT INTO quarantine_records (id, tenant_id, provider_id) "
                "VALUES ('q-1', 'tenant-a', 'mpesa')"
            )
        )
        connection.execute(
            text(
                """
                INSERT INTO quarantine_remediations (
                    id, tenant_id, quarantine_id, action, from_status,
                    to_status, actor_id, created_at
                ) VALUES (
                    'qr-1', 'tenant-a', 'q-1', 'review_started',
                    'open', 'reviewing', 'operator-1', CURRENT_TIMESTAMP
                )
                """
            )
        )
        with pytest.raises(IntegrityError, match="FOREIGN KEY"):
            connection.execute(
                text(
                    """
                    INSERT INTO quarantine_remediations (
                        id, tenant_id, quarantine_id, action, from_status,
                        to_status, actor_id, created_at
                    ) VALUES (
                        'qr-cross-tenant', 'tenant-b', 'q-1', 'review_started',
                        'open', 'reviewing', 'operator-2', CURRENT_TIMESTAMP
                    )
                    """
                )
            )
        with pytest.raises(IntegrityError, match="append-only"):
            connection.execute(
                    text("UPDATE quarantine_remediations SET action='resolved' WHERE id='qr-1'")
            )
        with pytest.raises(IntegrityError, match="append-only"):
            connection.execute(
                text("DELETE FROM quarantine_remediations WHERE id='qr-1'")
            )

        with Operations.context(MigrationContext.configure(connection)):
            with pytest.raises(RuntimeError, match="archive it before downgrade"):
                migration.downgrade()
        assert connection.execute(
            text("SELECT COUNT(*) FROM quarantine_remediations")
        ).scalar_one() == 1

        connection.exec_driver_sql("DROP TRIGGER trg_quarantine_remediations_immutable_delete")
        connection.exec_driver_sql("DELETE FROM quarantine_remediations")
        with Operations.context(MigrationContext.configure(connection)):
            migration.downgrade()
        assert connection.exec_driver_sql(
            "SELECT name FROM sqlite_master WHERE type='table' AND name='quarantine_remediations'"
        ).scalar_one_or_none() is None
        assert connection.exec_driver_sql(
            "SELECT name FROM pragma_table_info('transaction_provenances') "
            "WHERE name='dq_checks'"
        ).scalar_one_or_none() is None
        assert connection.exec_driver_sql(
            "SELECT name FROM sqlite_master WHERE type='index' "
            "AND name='ix_transaction_tenant_provider_currency_created'"
        ).scalar_one_or_none() is None
    engine.dispose()
