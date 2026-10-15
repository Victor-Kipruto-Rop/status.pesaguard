from __future__ import annotations

import importlib.util
from pathlib import Path

import pytest
from alembic.migration import MigrationContext
from alembic.operations import Operations
from sqlalchemy import create_engine, event, inspect, text
from sqlalchemy.exc import IntegrityError


MIGRATION_PATH = (
    Path(__file__).parents[1]
    / "alembic"
    / "versions"
    / "20261014_settlement_integrity.py"
)


def _load_migration():
    spec = importlib.util.spec_from_file_location("settlement_integrity", MIGRATION_PATH)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


@pytest.fixture()
def migrated_connection():
    engine = create_engine("sqlite:///:memory:")
    event.listen(
        engine,
        "connect",
        lambda dbapi_connection, _: dbapi_connection.execute("PRAGMA foreign_keys=ON"),
    )
    with engine.begin() as connection:
        connection.exec_driver_sql(
            "CREATE TABLE settlements ("
            "id TEXT PRIMARY KEY, tenant_id TEXT NOT NULL, "
            "status TEXT NOT NULL DEFAULT 'pending')"
        )
        connection.execute(
            text("INSERT INTO settlements (id, tenant_id) VALUES ('stl-1', 'tenant-a')")
        )
        with Operations.context(MigrationContext.configure(connection)):
            _load_migration().upgrade()
        yield connection
    engine.dispose()


def test_migration_adds_settlement_version_and_tenant_scoped_append_only_audit(
    migrated_connection,
):
    columns = {column["name"] for column in inspect(migrated_connection).get_columns("settlements")}
    assert "version" in columns
    assert (
        migrated_connection.execute(
            text("SELECT version FROM settlements WHERE id='stl-1'")
        ).scalar_one()
        == 1
    )

    migrated_connection.execute(
        text("UPDATE settlements SET status='calculating' WHERE id='stl-1'")
    )
    transition = migrated_connection.execute(
        text(
            "SELECT event_type, from_status, to_status, details "
            "FROM settlement_audit_events WHERE settlement_id='stl-1'"
        )
    ).mappings().one()
    assert transition["event_type"] == "status_transition"
    assert (transition["from_status"], transition["to_status"]) == ("pending", "calculating")

    with pytest.raises(IntegrityError, match="invalid settlement lifecycle transition"):
        migrated_connection.execute(
            text("UPDATE settlements SET status='settled' WHERE id='stl-1'")
        )

    migrated_connection.execute(
        text(
            "INSERT INTO settlement_audit_events "
            "(id, tenant_id, settlement_id, event_type, details, created_at) "
            "VALUES ('audit-1', 'tenant-a', 'stl-1', 'calculated', '{}', CURRENT_TIMESTAMP)"
        )
    )
    with pytest.raises(IntegrityError, match="settlement audit history is append-only"):
        migrated_connection.execute(
            text("UPDATE settlement_audit_events SET event_type='reconciled' WHERE id='audit-1'")
        )
    with pytest.raises(IntegrityError, match="settlement audit history is append-only"):
        migrated_connection.execute(
            text("DELETE FROM settlement_audit_events WHERE id='audit-1'")
        )


def test_migration_enforces_settlement_tenant_on_audit_rows(migrated_connection):
    with pytest.raises(IntegrityError):
        migrated_connection.execute(
            text(
                "INSERT INTO settlement_audit_events "
                "(id, tenant_id, settlement_id, event_type, details, created_at) "
                "VALUES ('audit-cross-tenant', 'tenant-b', 'stl-1', 'calculated', '{}', CURRENT_TIMESTAMP)"
            )
        )


def test_migration_downgrade_removes_settlement_audit_guards(migrated_connection):
    with Operations.context(MigrationContext.configure(migrated_connection)):
        _load_migration().downgrade()
    assert "settlement_audit_events" not in inspect(migrated_connection).get_table_names()
    assert "version" not in {
        column["name"] for column in inspect(migrated_connection).get_columns("settlements")
    }


def test_migration_refuses_downgrade_that_would_discard_audit_history(migrated_connection):
    migrated_connection.execute(
        text("UPDATE settlements SET status='calculating' WHERE id='stl-1'")
    )
    with Operations.context(MigrationContext.configure(migrated_connection)):
        with pytest.raises(RuntimeError, match="append-only settlement audit history exists"):
            _load_migration().downgrade()
    assert "settlement_audit_events" in inspect(migrated_connection).get_table_names()
    assert (
        migrated_connection.execute(
            text("SELECT COUNT(*) FROM settlement_audit_events WHERE settlement_id='stl-1'")
        ).scalar_one()
        == 1
    )
