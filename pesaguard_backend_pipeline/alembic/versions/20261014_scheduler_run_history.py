"""Add bounded scheduler retries and tenant-scoped execution history."""

from __future__ import annotations

import os

from alembic import op
import sqlalchemy as sa


revision = "20261014_scheduler_run_history"
down_revision = "20261014_webhook_delivery_recovery"
branch_labels = None
depends_on = None


def upgrade() -> None:
    with op.batch_alter_table("scheduled_jobs") as batch_op:
        batch_op.add_column(
            sa.Column(
                "retry_at",
                sa.DateTime(timezone=True),
                nullable=True,
            )
        )
        batch_op.add_column(
            sa.Column(
                "max_attempts",
                sa.Integer(),
                nullable=False,
                server_default="3",
            )
        )
        batch_op.add_column(
            sa.Column(
                "retry_backoff_seconds",
                sa.Integer(),
                nullable=False,
                server_default="30",
            )
        )
        batch_op.create_unique_constraint(
            "uq_scheduled_jobs_id_tenant",
            ["id", "tenant_id"],
        )
        batch_op.create_check_constraint(
            "ck_scheduled_jobs_max_attempts",
            "max_attempts BETWEEN 1 AND 5",
        )
        batch_op.create_check_constraint(
            "ck_scheduled_jobs_retry_backoff",
            "retry_backoff_seconds BETWEEN 1 AND 3600",
        )

    op.create_table(
        "scheduled_job_runs",
        sa.Column("id", sa.String(), nullable=False),
        sa.Column("job_id", sa.String(), nullable=False),
        sa.Column("tenant_id", sa.String(), nullable=False),
        sa.Column("job_key", sa.String(length=255), nullable=False),
        sa.Column("scheduled_for", sa.DateTime(timezone=True), nullable=False),
        sa.Column("attempt_number", sa.Integer(), nullable=False),
        sa.Column("status", sa.String(length=16), nullable=False),
        sa.Column("worker_id", sa.String(length=255), nullable=False),
        sa.Column("started_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("finished_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("error_type", sa.String(length=255), nullable=True),
        sa.Column("resolution_by", sa.String(length=255), nullable=True),
        sa.Column("resolution_reason", sa.Text(), nullable=True),
        sa.PrimaryKeyConstraint("id"),
        sa.ForeignKeyConstraint(
            ["job_id", "tenant_id"],
            ["scheduled_jobs.id", "scheduled_jobs.tenant_id"],
            name="fk_scheduled_job_runs_job_tenant",
        ),
        sa.UniqueConstraint(
            "job_id",
            "tenant_id",
            "scheduled_for",
            "attempt_number",
            name="uq_scheduled_job_runs_occurrence_attempt",
        ),
        sa.CheckConstraint(
            "tenant_id IS NOT NULL AND tenant_id <> ''",
            name="ck_scheduled_job_runs_tenant_id_nonempty",
        ),
        sa.CheckConstraint(
            "attempt_number BETWEEN 1 AND 5",
            name="ck_scheduled_job_runs_attempt_number",
        ),
        sa.CheckConstraint(
            "status IN ('running', 'interrupted', 'failed', 'succeeded')",
            name="ck_scheduled_job_runs_status",
        ),
    )
    op.create_index(
        "ix_scheduled_job_runs_tenant_job_started",
        "scheduled_job_runs",
        ["tenant_id", "job_key", "started_at"],
    )
    op.create_index(
        "ix_scheduled_job_runs_status_started",
        "scheduled_job_runs",
        ["status", "started_at"],
    )

    bind = op.get_bind()
    if bind.dialect.name == "postgresql":
        role_name = os.getenv("PESAGUARD_APPLICATION_DB_ROLE", "").strip()
        if not role_name:
            raise RuntimeError(
                "PESAGUARD_APPLICATION_DB_ROLE must name the runtime role "
                "that reads and records scheduled job attempts"
            )
        preparer = bind.dialect.identifier_preparer
        role = preparer.quote(role_name)
        table = preparer.quote("scheduled_job_runs")
        tenant_predicate = (
            "tenant_id = NULLIF(current_setting('app.tenant_id', true), '')"
        )
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


def downgrade() -> None:
    bind = op.get_bind()
    if bind.dialect.name == "postgresql":
        role_name = os.getenv("PESAGUARD_APPLICATION_DB_ROLE", "").strip()
        if role_name:
            preparer = bind.dialect.identifier_preparer
            op.execute(
                "REVOKE SELECT, INSERT, UPDATE ON "
                f"{preparer.quote('scheduled_job_runs')} "
                f"FROM {preparer.quote(role_name)}"
            )

    op.drop_index(
        "ix_scheduled_job_runs_status_started",
        table_name="scheduled_job_runs",
    )
    op.drop_index(
        "ix_scheduled_job_runs_tenant_job_started",
        table_name="scheduled_job_runs",
    )
    op.drop_table("scheduled_job_runs")

    with op.batch_alter_table("scheduled_jobs") as batch_op:
        batch_op.drop_constraint("ck_scheduled_jobs_retry_backoff", type_="check")
        batch_op.drop_constraint("ck_scheduled_jobs_max_attempts", type_="check")
        batch_op.drop_constraint("uq_scheduled_jobs_id_tenant", type_="unique")
        batch_op.drop_column("retry_backoff_seconds")
        batch_op.drop_column("max_attempts")
        batch_op.drop_column("retry_at")
