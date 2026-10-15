"""Add scheduler DAG dependencies, backfills, and execution timeouts."""

from __future__ import annotations

import os

from alembic import op
import sqlalchemy as sa


revision = "20261015_workflow_orchestration"
down_revision = "20261014_scheduler_run_history"
branch_labels = None
depends_on = None


def _configure_tenant_rls(bind: sa.engine.Connection, table_name: str) -> None:
    if bind.dialect.name != "postgresql":
        return
    role_name = os.getenv("PESAGUARD_APPLICATION_DB_ROLE", "").strip()
    if not role_name:
        raise RuntimeError(
            "PESAGUARD_APPLICATION_DB_ROLE must name the runtime role "
            "that reads and writes scheduler workflow records"
        )
    preparer = bind.dialect.identifier_preparer
    table = preparer.quote(table_name)
    role = preparer.quote(role_name)
    tenant_predicate = "tenant_id = NULLIF(current_setting('app.tenant_id', true), '')"
    op.execute(f"ALTER TABLE {table} ENABLE ROW LEVEL SECURITY")
    op.execute(f"ALTER TABLE {table} FORCE ROW LEVEL SECURITY")
    op.execute(
        f"CREATE POLICY pesaguard_tenant_isolation ON {table} "
        f"USING ({tenant_predicate}) WITH CHECK ({tenant_predicate})"
    )
    op.execute(
        f"CREATE POLICY pesaguard_tenant_guard ON {table} AS RESTRICTIVE "
        f"USING ({tenant_predicate}) WITH CHECK ({tenant_predicate})"
    )
    op.execute(f"GRANT SELECT, INSERT, UPDATE ON {table} TO {role}")


def upgrade() -> None:
    with op.batch_alter_table("scheduled_jobs") as batch_op:
        batch_op.add_column(sa.Column("timeout_seconds", sa.Integer(), nullable=True))
        batch_op.create_check_constraint(
            "ck_scheduled_jobs_timeout",
            "timeout_seconds IS NULL OR timeout_seconds BETWEEN 1 AND 3600",
        )

    op.create_table(
        "scheduled_job_dependencies",
        sa.Column("tenant_id", sa.String(), nullable=False),
        sa.Column("job_id", sa.String(), nullable=False),
        sa.Column("depends_on_job_id", sa.String(), nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            nullable=False,
            server_default=sa.func.now(),
        ),
        sa.PrimaryKeyConstraint("tenant_id", "job_id", "depends_on_job_id"),
        sa.ForeignKeyConstraint(
            ["job_id", "tenant_id"],
            ["scheduled_jobs.id", "scheduled_jobs.tenant_id"],
            name="fk_scheduled_job_dependencies_job_tenant",
        ),
        sa.ForeignKeyConstraint(
            ["depends_on_job_id", "tenant_id"],
            ["scheduled_jobs.id", "scheduled_jobs.tenant_id"],
            name="fk_scheduled_job_dependencies_parent_tenant",
        ),
        sa.CheckConstraint(
            "job_id <> depends_on_job_id",
            name="ck_scheduled_job_dependencies_not_self",
        ),
    )
    op.create_index(
        "ix_scheduled_job_dependencies_parent",
        "scheduled_job_dependencies",
        ["tenant_id", "depends_on_job_id"],
    )

    op.create_table(
        "scheduled_job_backfills",
        sa.Column("id", sa.String(), nullable=False),
        sa.Column("tenant_id", sa.String(), nullable=False),
        sa.Column("job_id", sa.String(), nullable=False),
        sa.Column("job_key", sa.String(length=255), nullable=False),
        sa.Column("start_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("end_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("idempotency_key", sa.String(length=255), nullable=False),
        sa.Column("status", sa.String(length=16), nullable=False, server_default="queued"),
        sa.Column("attempt_count", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("max_attempts", sa.Integer(), nullable=False, server_default="3"),
        sa.Column("retry_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("requested_by", sa.String(length=255), nullable=False),
        sa.Column("reason", sa.Text(), nullable=False),
        sa.Column("error_type", sa.String(length=255), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("id", "tenant_id", name="uq_scheduled_job_backfills_id_tenant"),
        sa.UniqueConstraint(
            "tenant_id",
            "job_id",
            "idempotency_key",
            name="uq_scheduled_job_backfills_idempotency",
        ),
        sa.ForeignKeyConstraint(
            ["job_id", "tenant_id"],
            ["scheduled_jobs.id", "scheduled_jobs.tenant_id"],
            name="fk_scheduled_job_backfills_job_tenant",
        ),
        sa.CheckConstraint(
            "status IN ('queued', 'running', 'retrying', 'succeeded', 'failed', 'interrupted')",
            name="ck_scheduled_job_backfills_status",
        ),
        sa.CheckConstraint(
            "attempt_count BETWEEN 0 AND 5",
            name="ck_scheduled_job_backfills_attempt_count",
        ),
        sa.CheckConstraint(
            "max_attempts BETWEEN 1 AND 5",
            name="ck_scheduled_job_backfills_max_attempts",
        ),
        sa.CheckConstraint("end_at > start_at", name="ck_scheduled_job_backfills_window"),
    )
    op.create_index(
        "ix_scheduled_job_backfills_due",
        "scheduled_job_backfills",
        ["tenant_id", "status", "retry_at"],
    )
    op.create_index(
        "ix_scheduled_job_backfills_job_created",
        "scheduled_job_backfills",
        ["tenant_id", "job_id", "created_at"],
    )

    with op.batch_alter_table("scheduled_job_runs") as batch_op:
        batch_op.add_column(
            sa.Column("trigger_type", sa.String(length=16), nullable=False, server_default="scheduled")
        )
        batch_op.add_column(sa.Column("backfill_id", sa.String(), nullable=True))
        batch_op.create_foreign_key(
            "fk_scheduled_job_runs_backfill_tenant",
            "scheduled_job_backfills",
            ["backfill_id", "tenant_id"],
            ["id", "tenant_id"],
        )
        batch_op.create_check_constraint(
            "ck_scheduled_job_runs_trigger_type",
            "trigger_type IN ('scheduled', 'backfill')",
        )
        batch_op.create_check_constraint(
            "ck_scheduled_job_runs_backfill_reference",
            "(trigger_type = 'scheduled' AND backfill_id IS NULL) OR "
            "(trigger_type = 'backfill' AND backfill_id IS NOT NULL)",
        )

    bind = op.get_bind()
    _configure_tenant_rls(bind, "scheduled_job_dependencies")
    _configure_tenant_rls(bind, "scheduled_job_backfills")
    if bind.dialect.name == "postgresql":
        role_name = os.getenv("PESAGUARD_APPLICATION_DB_ROLE", "").strip()
        if not role_name:
            raise RuntimeError(
                "PESAGUARD_APPLICATION_DB_ROLE must name the runtime role "
                "that reads and writes scheduled jobs"
            )
        role = bind.dialect.identifier_preparer.quote(role_name)
        op.execute(f"GRANT SELECT, INSERT, UPDATE ON scheduled_jobs TO {role}")


def downgrade() -> None:
    bind = op.get_bind()
    if bind.dialect.name == "postgresql":
        role_name = os.getenv("PESAGUARD_APPLICATION_DB_ROLE", "").strip()
        if role_name:
            preparer = bind.dialect.identifier_preparer
            role = preparer.quote(role_name)
            for table_name in (
                "scheduled_jobs",
                "scheduled_job_dependencies",
                "scheduled_job_backfills",
            ):
                op.execute(
                    f"REVOKE SELECT, INSERT, UPDATE ON "
                    f"{preparer.quote(table_name)} FROM {role}"
                )

    with op.batch_alter_table("scheduled_job_runs") as batch_op:
        batch_op.drop_constraint("ck_scheduled_job_runs_backfill_reference", type_="check")
        batch_op.drop_constraint("ck_scheduled_job_runs_trigger_type", type_="check")
        batch_op.drop_constraint("fk_scheduled_job_runs_backfill_tenant", type_="foreignkey")
        batch_op.drop_column("backfill_id")
        batch_op.drop_column("trigger_type")

    op.drop_index(
        "ix_scheduled_job_backfills_job_created",
        table_name="scheduled_job_backfills",
    )
    op.drop_index("ix_scheduled_job_backfills_due", table_name="scheduled_job_backfills")
    op.drop_table("scheduled_job_backfills")
    op.drop_index(
        "ix_scheduled_job_dependencies_parent",
        table_name="scheduled_job_dependencies",
    )
    op.drop_table("scheduled_job_dependencies")

    with op.batch_alter_table("scheduled_jobs") as batch_op:
        batch_op.drop_constraint("ck_scheduled_jobs_timeout", type_="check")
        batch_op.drop_column("timeout_seconds")
