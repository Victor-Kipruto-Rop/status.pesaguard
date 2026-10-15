from __future__ import annotations

import importlib.util
from pathlib import Path
import sys

import pytest
from alembic.migration import MigrationContext
from alembic.operations import Operations
from sqlalchemy import create_engine, inspect

import webhook_operations_worker
from models import Base
from tenant_context import current_tenant_id


def test_worker_runs_recovery_and_health_with_tenant_scope(monkeypatch):
    engine = create_engine("sqlite://")
    Base.metadata.create_all(engine)
    monkeypatch.setenv("DATABASE_URL", "sqlite://")
    monkeypatch.setattr(webhook_operations_worker, "list_active_tenant_ids", lambda: ["tenant-a"])
    monkeypatch.setattr(webhook_operations_worker, "create_database_engine", lambda _: engine)

    observed = {}

    def recover(self, tenant_id, **kwargs):
        observed["recovery_tenant"] = tenant_id
        observed["recovery_context"] = current_tenant_id()
        observed["recovery_args"] = kwargs
        return [{"id": "delivery-1", "status": "success"}]

    def check_health(self, tenant_id):
        observed["health_tenant"] = tenant_id
        observed["health_context"] = current_tenant_id()
        return {"status": "ok"}

    monkeypatch.setattr(webhook_operations_worker.WebhookManager, "recover_stale_deliveries", recover)
    monkeypatch.setattr(webhook_operations_worker.EscalationEngine, "check_webhook_health", check_health)

    result = webhook_operations_worker.run_once(batch_size=20, max_age_seconds=600)

    assert observed == {
        "recovery_tenant": "tenant-a",
        "recovery_context": "tenant-a",
        "recovery_args": {"max_age_seconds": 600, "batch_size": 20},
        "health_tenant": "tenant-a",
        "health_context": "tenant-a",
    }
    assert result == [{
        "tenant_id": "tenant-a",
        "recovery_count": 1,
        "recovery_outcomes": [{"id": "delivery-1", "status": "success"}],
        "health": {"status": "ok"},
    }]
    engine.dispose()


def test_worker_rejects_invalid_limits():
    with pytest.raises(ValueError, match="batch_size"):
        webhook_operations_worker.run_once(batch_size=0)
    with pytest.raises(ValueError, match="max_age_seconds"):
        webhook_operations_worker.run_once(max_age_seconds=59)


def test_worker_main_reports_invalid_tick_arguments(monkeypatch):
    monkeypatch.setattr(sys, "argv", ["webhook-operations", "--batch-size", "0"])

    assert webhook_operations_worker.main() == 1


def test_worker_continues_other_tenants_after_a_scoped_failure(monkeypatch):
    engine = create_engine("sqlite://")
    Base.metadata.create_all(engine)
    monkeypatch.setenv("DATABASE_URL", "sqlite://")
    monkeypatch.setattr(
        webhook_operations_worker,
        "list_active_tenant_ids",
        lambda: ["tenant-a", "tenant-b"],
    )
    monkeypatch.setattr(webhook_operations_worker, "create_database_engine", lambda _: engine)

    def recover(self, tenant_id, **kwargs):
        if tenant_id == "tenant-a":
            raise RuntimeError("simulated tenant outage")
        return []

    monkeypatch.setattr(webhook_operations_worker.WebhookManager, "recover_stale_deliveries", recover)
    monkeypatch.setattr(
        webhook_operations_worker.EscalationEngine,
        "check_webhook_health",
        lambda self, tenant_id: {"status": "ok"},
    )

    result = webhook_operations_worker.run_once()

    assert result == [
        {"tenant_id": "tenant-a", "status": "error"},
        {
            "tenant_id": "tenant-b",
            "recovery_count": 0,
            "recovery_outcomes": [],
            "health": {"status": "ok"},
        },
    ]
    engine.dispose()


def test_recovery_migration_adds_and_drops_recovery_state_index():
    migration_path = (
        Path(__file__).resolve().parents[1]
        / "alembic"
        / "versions"
        / "20261014_webhook_delivery_recovery.py"
    )
    spec = importlib.util.spec_from_file_location("webhook_delivery_recovery_migration", migration_path)
    assert spec and spec.loader
    migration = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(migration)

    engine = create_engine("sqlite://")
    with engine.begin() as connection:
        connection.exec_driver_sql(
            "CREATE TABLE webhook_deliveries (id VARCHAR PRIMARY KEY, status VARCHAR NOT NULL)"
        )
        with Operations.context(MigrationContext.configure(connection)):
            migration.upgrade()
        columns = {column["name"] for column in inspect(connection).get_columns("webhook_deliveries")}
        indexes = {index["name"] for index in inspect(connection).get_indexes("webhook_deliveries")}
        assert "updated_at" in columns
        assert "ix_webhook_deliveries_recovery" in indexes

        with Operations.context(MigrationContext.configure(connection)):
            migration.downgrade()
        columns_after_downgrade = {
            column["name"] for column in inspect(connection).get_columns("webhook_deliveries")
        }
        assert "updated_at" not in columns_after_downgrade
    engine.dispose()


def test_recovery_migration_creates_delivery_table_when_missing():
    migration_path = (
        Path(__file__).resolve().parents[1]
        / "alembic"
        / "versions"
        / "20261014_webhook_delivery_recovery.py"
    )
    spec = importlib.util.spec_from_file_location("webhook_delivery_recovery_bootstrap", migration_path)
    assert spec and spec.loader
    migration = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(migration)

    engine = create_engine("sqlite://")
    with engine.begin() as connection:
        with Operations.context(MigrationContext.configure(connection)):
            migration.upgrade()

        inspector = inspect(connection)
        columns = {
            column["name"] for column in inspector.get_columns("webhook_deliveries")
        }
        indexes = {
            index["name"] for index in inspector.get_indexes("webhook_deliveries")
        }
        constraints = {
            constraint["name"]
            for constraint in inspector.get_check_constraints("webhook_deliveries")
        }
        assert {
            "id",
            "tenant_id",
            "webhook_id",
            "event_type",
            "payload",
            "status",
            "updated_at",
        }.issubset(columns)
        assert "ix_webhook_deliveries_recovery" in indexes
        assert "ix_webhook_deliveries_tenant_webhook" in indexes
        assert "ck_webhook_deliveries_tenant_id_nonempty" in constraints

        with Operations.context(MigrationContext.configure(connection)):
            migration.downgrade()
        assert inspect(connection).has_table("webhook_deliveries")
        remaining_columns = {
            column["name"]
            for column in inspect(connection).get_columns("webhook_deliveries")
        }
        remaining_indexes = {
            index["name"]
            for index in inspect(connection).get_indexes("webhook_deliveries")
        }
        assert "updated_at" not in remaining_columns
        assert "ix_webhook_deliveries_recovery" not in remaining_indexes
    engine.dispose()
