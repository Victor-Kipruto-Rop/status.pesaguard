from __future__ import annotations

import importlib.util
from pathlib import Path

from alembic.migration import MigrationContext
from alembic.operations import Operations
from sqlalchemy import create_engine, inspect


def test_scheduler_history_migration_upgrades_and_downgrades_sqlite():
    migration_path = (
        Path(__file__).resolve().parents[1]
        / "alembic"
        / "versions"
        / "20261014_scheduler_run_history.py"
    )
    spec = importlib.util.spec_from_file_location(
        "scheduler_run_history_migration",
        migration_path,
    )
    assert spec and spec.loader
    migration = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(migration)
    workflow_migration_path = (
        Path(__file__).resolve().parents[1]
        / "alembic"
        / "versions"
        / "20261015_workflow_orchestration.py"
    )
    workflow_spec = importlib.util.spec_from_file_location(
        "workflow_orchestration_migration",
        workflow_migration_path,
    )
    assert workflow_spec and workflow_spec.loader
    workflow_migration = importlib.util.module_from_spec(workflow_spec)
    workflow_spec.loader.exec_module(workflow_migration)

    engine = create_engine("sqlite://")
    with engine.begin() as connection:
        connection.exec_driver_sql(
            """
            CREATE TABLE scheduled_jobs (
                id VARCHAR PRIMARY KEY,
                tenant_id VARCHAR NOT NULL,
                job_key VARCHAR(255) NOT NULL,
                task_name VARCHAR(255) NOT NULL,
                interval_seconds INTEGER NOT NULL,
                enabled BOOLEAN NOT NULL DEFAULT 1,
                next_run_at DATETIME,
                last_run_at DATETIME,
                locked_until DATETIME,
                lock_owner VARCHAR(255),
                run_count INTEGER NOT NULL DEFAULT 0,
                failure_count INTEGER NOT NULL DEFAULT 0,
                last_error TEXT,
                payload JSON NOT NULL DEFAULT '{}',
                created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                CONSTRAINT uq_scheduled_jobs_tenant_key UNIQUE (tenant_id, job_key)
            )
            """
        )
        with Operations.context(MigrationContext.configure(connection)):
            migration.upgrade()

        inspector = inspect(connection)
        job_columns = {
            column["name"] for column in inspector.get_columns("scheduled_jobs")
        }
        assert {"retry_at", "max_attempts", "retry_backoff_seconds"} <= job_columns
        run_columns = {
            column["name"] for column in inspector.get_columns("scheduled_job_runs")
        }
        assert {
            "tenant_id",
            "job_id",
            "scheduled_for",
            "attempt_number",
            "status",
            "started_at",
        } <= run_columns
        assert {
            "ix_scheduled_job_runs_tenant_job_started",
            "ix_scheduled_job_runs_status_started",
        } <= {
            index["name"] for index in inspector.get_indexes("scheduled_job_runs")
        }
        assert any(
            foreign_key["name"] == "fk_scheduled_job_runs_job_tenant"
            for foreign_key in inspector.get_foreign_keys("scheduled_job_runs")
        )

        with Operations.context(MigrationContext.configure(connection)):
            workflow_migration.upgrade()

        inspector = inspect(connection)
        assert "timeout_seconds" in {
            column["name"] for column in inspector.get_columns("scheduled_jobs")
        }
        assert inspector.has_table("scheduled_job_dependencies")
        assert inspector.has_table("scheduled_job_backfills")
        run_columns = {
            column["name"] for column in inspector.get_columns("scheduled_job_runs")
        }
        assert {"trigger_type", "backfill_id"} <= run_columns
        assert any(
            foreign_key["name"] == "fk_scheduled_job_runs_backfill_tenant"
            for foreign_key in inspector.get_foreign_keys("scheduled_job_runs")
        )

        with Operations.context(MigrationContext.configure(connection)):
            workflow_migration.downgrade()
        assert not inspect(connection).has_table("scheduled_job_backfills")
        assert not inspect(connection).has_table("scheduled_job_dependencies")

        with Operations.context(MigrationContext.configure(connection)):
            migration.downgrade()

        assert not inspect(connection).has_table("scheduled_job_runs")
        remaining_columns = {
            column["name"] for column in inspect(connection).get_columns("scheduled_jobs")
        }
        assert not {"retry_at", "max_attempts", "retry_backoff_seconds"} & remaining_columns
    engine.dispose()
