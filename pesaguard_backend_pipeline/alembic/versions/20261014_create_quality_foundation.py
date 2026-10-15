"""Create core financial and data-quality evidence tables."""

from alembic import op
import sqlalchemy as sa


revision = "20261014_create_quality_foundation"
down_revision = "20261014_enforce_reconciliation_integrity"
branch_labels = None
depends_on = None


def _tables() -> set[str]:
    return set(sa.inspect(op.get_bind()).get_table_names())


def upgrade() -> None:
    tables = _tables()
    if "internal_records" not in tables:
        op.create_table(
            "internal_records",
            sa.Column("internal_ref", sa.String(), nullable=False),
            sa.Column("tenant_id", sa.String(), nullable=False, server_default="default"),
            sa.Column("amount", sa.Numeric(19, 3), nullable=False),
            sa.Column("currency", sa.String(3), nullable=False, server_default="KES"),
            sa.Column("phone_number", sa.String(), nullable=False),
            sa.Column("status", sa.String(), nullable=False),
            sa.Column(
                "synced_at",
                sa.DateTime(timezone=True),
                nullable=False,
                server_default=sa.func.now(),
            ),
            sa.CheckConstraint(
                "tenant_id IS NOT NULL AND tenant_id <> ''",
                name="ck_internal_records_tenant_id_nonempty",
            ),
            sa.CheckConstraint(
                "length(currency) = 3 AND currency = upper(currency)",
                name="ck_internal_records_currency_iso",
            ),
            sa.PrimaryKeyConstraint("internal_ref"),
        )
        op.create_index(
            "ix_internal_records_tenant_phone",
            "internal_records",
            ["tenant_id", "phone_number"],
        )
        op.create_index("ix_internal_records_phone", "internal_records", ["phone_number"])
        op.create_index("ix_internal_records_synced", "internal_records", ["synced_at"])

    if "transaction_provenances" not in tables:
        op.create_table(
            "transaction_provenances",
            sa.Column("id", sa.String(), nullable=False),
            sa.Column("tenant_id", sa.String(), nullable=False),
            sa.Column("transaction_id", sa.String(), nullable=False),
            sa.Column("provider_id", sa.String(), nullable=False),
            sa.Column("lineage_id", sa.String(), nullable=False),
            sa.Column("event_id", sa.String(), nullable=False),
            sa.Column("schema_version", sa.String(64), nullable=False, server_default="1.0"),
            sa.Column("pipeline_version", sa.String(64), nullable=False, server_default="1.0"),
            sa.Column("raw_payload_sha256", sa.String(64), nullable=False),
            sa.Column("provider_payload_sha256", sa.String(64), nullable=True),
            sa.Column("provider_source_ip", sa.String(), nullable=True),
            sa.Column(
                "provider_signature_verified",
                sa.Boolean(),
                nullable=False,
                server_default=sa.false(),
            ),
            sa.Column("normalized_payload_sha256", sa.String(64), nullable=True),
            sa.Column("normalized_at", sa.DateTime(timezone=True), nullable=True),
            sa.Column("fraud_decision", sa.String(), nullable=True),
            sa.Column("fraud_checked_at", sa.DateTime(timezone=True), nullable=True),
            sa.Column("reconciliation_decision", sa.String(), nullable=True),
            sa.Column("reconciliation_checked_at", sa.DateTime(timezone=True), nullable=True),
            sa.Column("final_decision", sa.String(), nullable=True),
            sa.Column("dq_status", sa.String(16), nullable=False, server_default="pass"),
            sa.Column("dq_completeness", sa.Numeric(5, 4), nullable=False, server_default="1.0"),
            sa.Column("dq_validity", sa.Numeric(5, 4), nullable=False, server_default="1.0"),
            sa.Column("dq_accuracy", sa.Numeric(5, 4), nullable=False, server_default="1.0"),
            sa.Column("dq_timeliness", sa.Numeric(5, 4), nullable=False, server_default="1.0"),
            sa.Column("dq_consistency", sa.Numeric(5, 4), nullable=False, server_default="1.0"),
            sa.Column("dq_uniqueness", sa.Numeric(5, 4), nullable=False, server_default="1.0"),
            sa.Column("dq_failures", sa.JSON(), nullable=False, server_default="[]"),
            sa.Column("dq_warnings", sa.JSON(), nullable=False, server_default="[]"),
            sa.Column("dq_checked_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
            sa.Column("lineage_snapshot", sa.JSON(), nullable=False, server_default="[]"),
            sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
            sa.Column("finalized_at", sa.DateTime(timezone=True), nullable=True),
            sa.CheckConstraint(
                "tenant_id IS NOT NULL AND tenant_id <> ''",
                name="ck_prov_tenant_id_nonempty",
            ),
            sa.PrimaryKeyConstraint("id"),
        )
        op.create_index(
            "ix_prov_tenant_final",
            "transaction_provenances",
            ["tenant_id", "final_decision"],
        )
        op.create_index(
            "ix_prov_tenant_created",
            "transaction_provenances",
            ["tenant_id", "created_at"],
        )

    if "quarantine_records" not in tables:
        op.create_table(
            "quarantine_records",
            sa.Column("id", sa.String(), nullable=False),
            sa.Column("tenant_id", sa.String(), nullable=False),
            sa.Column("transaction_id", sa.String(), nullable=True),
            sa.Column("provider_id", sa.String(), nullable=False),
            sa.Column("lineage_id", sa.String(), nullable=True),
            sa.Column("reason", sa.String(), nullable=False),
            sa.Column("failure_rule", sa.String(), nullable=True),
            sa.Column("payload", sa.JSON(), nullable=False),
            sa.Column("payload_sha256", sa.String(64), nullable=False),
            sa.Column("rejection_context", sa.JSON(), nullable=True),
            sa.Column("source_ip", sa.String(), nullable=True),
            sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
            sa.CheckConstraint(
                "tenant_id IS NOT NULL AND tenant_id <> ''",
                name="ck_quarantine_tenant_id_nonempty",
            ),
            sa.PrimaryKeyConstraint("id"),
        )
        op.create_index(
            "ix_quarantine_tenant_reason",
            "quarantine_records",
            ["tenant_id", "reason"],
        )


def downgrade() -> None:
    """Retain financial and quality evidence tables and their data."""
