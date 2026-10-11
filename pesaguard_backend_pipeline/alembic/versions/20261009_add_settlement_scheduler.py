"""Add settlements, settlement_lines, and scheduled_jobs tables.

Settlement tables carry NUMERIC money columns, non-empty tenant_id check
constraints, and a unique (tenant_id, settlement_key) constraint so replayed
settlement jobs cannot double-count. scheduled_jobs powers the durable
scheduler (at-most-once across workers via distributed locks at runtime).
"""

from alembic import op
import sqlalchemy as sa


revision = "20261009_settlement_scheduler"
down_revision = "20261007_api_key_sync_idem"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "settlements",
        sa.Column("id", sa.String(), nullable=False),
        sa.Column("tenant_id", sa.String(), nullable=False),
        sa.Column("settlement_key", sa.String(length=255), nullable=False),
        sa.Column("currency", sa.String(length=8), nullable=False, server_default="KES"),
        sa.Column("period_start", sa.DateTime(timezone=True), nullable=False),
        sa.Column("period_end", sa.DateTime(timezone=True), nullable=False),
        sa.Column("status", sa.String(length=32), nullable=False, server_default="pending"),
        sa.Column("transaction_count", sa.Integer(), nullable=False, server_default=sa.text("0")),
        sa.Column("gross_amount", sa.Numeric(18, 2), nullable=False, server_default=sa.text("0")),
        sa.Column("fee_amount", sa.Numeric(18, 2), nullable=False, server_default=sa.text("0")),
        sa.Column("net_amount", sa.Numeric(18, 2), nullable=False, server_default=sa.text("0")),
        sa.Column("expected_net_amount", sa.Numeric(18, 2), nullable=True),
        sa.Column("variance_amount", sa.Numeric(18, 2), nullable=True),
        sa.Column("computed_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("reconciled_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("settled_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
        sa.Column("notes", sa.Text(), nullable=True),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("tenant_id", "settlement_key", name="uq_settlements_tenant_key"),
        sa.CheckConstraint("tenant_id IS NOT NULL AND tenant_id <> ''", name="ck_settlements_tenant_id_nonempty"),
        sa.CheckConstraint("gross_amount >= 0", name="ck_settlements_gross_nonnegative"),
        sa.CheckConstraint("fee_amount >= 0", name="ck_settlements_fee_nonnegative"),
        sa.CheckConstraint("net_amount >= 0", name="ck_settlements_net_nonnegative"),
        sa.CheckConstraint("transaction_count >= 0", name="ck_settlements_count_nonnegative"),
        sa.CheckConstraint("currency <> ''", name="ck_settlements_currency_nonempty"),
        sa.CheckConstraint("period_end >= period_start", name="ck_settlements_period_ordered"),
        sa.CheckConstraint(
            "status IN ('pending','calculating','calculated','reconciling',"
            "'reconciled','settled','failed','disputed')",
            name="ck_settlements_status_valid",
        ),
    )
    op.create_index(
        "ix_settlements_tenant_period", "settlements", ["tenant_id", "period_start", "period_end"]
    )
    op.create_index("ix_settlements_tenant_status", "settlements", ["tenant_id", "status"])

    op.create_table(
        "settlement_lines",
        sa.Column("id", sa.String(), nullable=False),
        sa.Column("tenant_id", sa.String(), nullable=False),
        sa.Column("settlement_id", sa.String(), nullable=False),
        sa.Column("transaction_id", sa.String(), nullable=False),
        sa.Column("trans_id", sa.String(), nullable=True),
        sa.Column("provider", sa.String(), nullable=True),
        sa.Column("amount", sa.Numeric(18, 2), nullable=False, server_default=sa.text("0")),
        sa.Column("fee", sa.Numeric(18, 2), nullable=False, server_default=sa.text("0")),
        sa.Column("net", sa.Numeric(18, 2), nullable=False, server_default=sa.text("0")),
        sa.Column("status", sa.String(length=32), nullable=False, server_default="included"),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("settlement_id", "transaction_id", name="uq_settlement_lines_settlement_tx"),
        sa.CheckConstraint("tenant_id IS NOT NULL AND tenant_id <> ''", name="ck_settlement_lines_tenant_id_nonempty"),
        sa.CheckConstraint("amount >= 0", name="ck_settlement_lines_amount_nonnegative"),
        sa.CheckConstraint("fee >= 0", name="ck_settlement_lines_fee_nonnegative"),
        sa.CheckConstraint("net >= 0", name="ck_settlement_lines_net_nonnegative"),
    )
    op.create_index(
        "ix_settlement_lines_tenant_settlement", "settlement_lines", ["tenant_id", "settlement_id"]
    )

    op.create_table(
        "scheduled_jobs",
        sa.Column("id", sa.String(), nullable=False),
        sa.Column("tenant_id", sa.String(), nullable=False),
        sa.Column("job_key", sa.String(length=255), nullable=False),
        sa.Column("task_name", sa.String(length=255), nullable=False),
        sa.Column("interval_seconds", sa.Integer(), nullable=False),
        sa.Column("enabled", sa.Boolean(), nullable=False, server_default=sa.text("true")),
        sa.Column("next_run_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("last_run_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("locked_until", sa.DateTime(timezone=True), nullable=True),
        sa.Column("lock_owner", sa.String(length=255), nullable=True),
        sa.Column("run_count", sa.Integer(), nullable=False, server_default=sa.text("0")),
        sa.Column("failure_count", sa.Integer(), nullable=False, server_default=sa.text("0")),
        sa.Column("last_error", sa.Text(), nullable=True),
        sa.Column("payload", sa.JSON(), nullable=False, server_default=sa.text("'{}'")),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("tenant_id", "job_key", name="uq_scheduled_jobs_tenant_key"),
        sa.CheckConstraint("tenant_id IS NOT NULL AND tenant_id <> ''", name="ck_scheduled_jobs_tenant_id_nonempty"),
        sa.CheckConstraint("interval_seconds > 0", name="ck_scheduled_jobs_interval_positive"),
        sa.CheckConstraint("run_count >= 0", name="ck_scheduled_jobs_run_count_nonnegative"),
        sa.CheckConstraint("failure_count >= 0", name="ck_scheduled_jobs_failure_count_nonnegative"),
    )
    op.create_index("ix_scheduled_jobs_due", "scheduled_jobs", ["enabled", "next_run_at"])
    op.create_index("ix_scheduled_jobs_tenant_task", "scheduled_jobs", ["tenant_id", "task_name"])


def downgrade() -> None:
    op.drop_index("ix_scheduled_jobs_tenant_task", table_name="scheduled_jobs")
    op.drop_index("ix_scheduled_jobs_due", table_name="scheduled_jobs")
    op.drop_table("scheduled_jobs")
    op.drop_index("ix_settlement_lines_tenant_settlement", table_name="settlement_lines")
    op.drop_table("settlement_lines")
    op.drop_index("ix_settlements_tenant_status", table_name="settlements")
    op.drop_index("ix_settlements_tenant_period", table_name="settlements")
    op.drop_table("settlements")
