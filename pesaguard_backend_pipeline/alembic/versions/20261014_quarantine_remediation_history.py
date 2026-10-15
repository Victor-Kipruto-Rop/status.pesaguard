"""Add tenant-scoped append-only quarantine remediation history."""

from alembic import op
import sqlalchemy as sa


revision = "20261014_quarantine_remediation_history"
down_revision = "20261014_create_quality_foundation"
branch_labels = None
depends_on = None


def upgrade() -> None:
    bind = op.get_bind()
    inspector = sa.inspect(bind)
    tables = set(inspector.get_table_names())
    transaction_indexes = {item["name"] for item in inspector.get_indexes("transactions")}
    if "ix_transaction_tenant_provider_currency_created" not in transaction_indexes:
        op.create_index(
            "ix_transaction_tenant_provider_currency_created",
            "transactions",
            ["tenant_id", "provider", "currency", "created_at"],
            unique=False,
        )

    provenance_columns = {
        column["name"] for column in inspector.get_columns("transaction_provenances")
    }
    if "dq_overall_score" not in provenance_columns:
        op.add_column(
            "transaction_provenances",
            sa.Column(
                "dq_overall_score",
                sa.Numeric(precision=5, scale=4),
                nullable=False,
                server_default="1.0",
            ),
        )
    if "dq_checks" not in provenance_columns:
        op.add_column(
            "transaction_provenances",
            sa.Column("dq_checks", sa.JSON(), nullable=False, server_default="[]"),
        )

    quarantine_columns = {
        column["name"] for column in inspector.get_columns("quarantine_records")
    }
    if "deduplication_key" not in quarantine_columns:
        op.add_column(
            "quarantine_records",
            sa.Column("deduplication_key", sa.String(length=64), nullable=True),
        )
    quarantine_indexes = {item["name"] for item in inspector.get_indexes("quarantine_records")}
    quarantine_constraints = {
        item["name"] for item in inspector.get_unique_constraints("quarantine_records")
    }
    if "uq_quarantine_records_id_tenant" not in quarantine_indexes | quarantine_constraints:
        op.create_index(
            "uq_quarantine_records_id_tenant",
            "quarantine_records",
            ["id", "tenant_id"],
            unique=True,
        )
    if "uq_quarantine_records_deduplication_key" not in quarantine_indexes | quarantine_constraints:
        op.create_index(
            "uq_quarantine_records_deduplication_key",
            "quarantine_records",
            ["tenant_id", "provider_id", "deduplication_key"],
            unique=True,
        )

    if "quarantine_remediations" not in tables:
        op.create_table(
            "quarantine_remediations",
            sa.Column("id", sa.String(), nullable=False),
            sa.Column("tenant_id", sa.String(), nullable=False),
            sa.Column("quarantine_id", sa.String(), nullable=False),
            sa.Column("action", sa.String(length=32), nullable=False),
            sa.Column("from_status", sa.String(length=24), nullable=True),
            sa.Column("to_status", sa.String(length=24), nullable=False),
            sa.Column("actor_id", sa.String(length=128), nullable=False),
            sa.Column("evidence_sha256", sa.String(length=64), nullable=True),
            sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
            sa.CheckConstraint(
                "tenant_id IS NOT NULL AND tenant_id <> ''",
                name="ck_quarantine_remediations_tenant_id_nonempty",
            ),
            sa.CheckConstraint(
                "action IN ('review_started', 'correction_recorded', "
                "'replay_requested', 'replay_completed', 'resolved', 'false_positive')",
                name="ck_quarantine_remediations_action",
            ),
            sa.CheckConstraint(
                "to_status IN ('reviewing', 'corrected', 'replay_pending', "
                "'resolved', 'false_positive')",
                name="ck_quarantine_remediations_status",
            ),
            sa.CheckConstraint(
                "(action = 'review_started' AND from_status = 'open' AND to_status = 'reviewing') "
                "OR (action = 'correction_recorded' AND from_status IN ('open', 'reviewing') "
                "AND to_status = 'corrected') "
                "OR (action = 'replay_requested' AND from_status = 'corrected' "
                "AND to_status = 'replay_pending') "
                "OR (action = 'replay_completed' AND from_status = 'replay_pending' "
                "AND to_status = 'resolved') "
                "OR (action = 'resolved' AND from_status IN "
                "('open', 'reviewing', 'corrected', 'replay_pending') AND to_status = 'resolved') "
                "OR (action = 'false_positive' AND from_status IN "
                "('open', 'reviewing', 'corrected') AND to_status = 'false_positive')",
                name="ck_quarantine_remediations_transition",
            ),
            sa.ForeignKeyConstraint(
                ["quarantine_id", "tenant_id"],
                ["quarantine_records.id", "quarantine_records.tenant_id"],
                name="fk_quarantine_remediations_quarantine",
                ondelete="RESTRICT",
            ),
            sa.PrimaryKeyConstraint("id"),
        )
    if "ix_quarantine_remediations_scope_history" not in {
        item["name"] for item in sa.inspect(op.get_bind()).get_indexes("quarantine_remediations")
    }:
        op.create_index(
            "ix_quarantine_remediations_scope_history",
            "quarantine_remediations",
            ["tenant_id", "quarantine_id", "created_at"],
            unique=False,
        )

    dialect = op.get_bind().dialect.name
    if dialect == "sqlite":
        triggers = {
            row[0]
            for row in op.get_bind().execute(
                sa.text("SELECT name FROM sqlite_master WHERE type = 'trigger'")
            )
        }
        if "trg_quarantine_remediations_immutable_update" not in triggers:
            op.execute(
                "CREATE TRIGGER trg_quarantine_remediations_immutable_update "
                "BEFORE UPDATE ON quarantine_remediations "
                "BEGIN SELECT RAISE(ABORT, 'quarantine remediation history is append-only'); END"
            )
        if "trg_quarantine_remediations_immutable_delete" not in triggers:
            op.execute(
                "CREATE TRIGGER trg_quarantine_remediations_immutable_delete "
                "BEFORE DELETE ON quarantine_remediations "
                "BEGIN SELECT RAISE(ABORT, 'quarantine remediation history is append-only'); END"
            )
        return
    if dialect != "postgresql":
        raise RuntimeError(
            f"quarantine remediation history is unsupported for database dialect {dialect}"
        )

    op.execute(
        """
        CREATE OR REPLACE FUNCTION pesaguard_reject_quarantine_remediation_mutation()
        RETURNS trigger AS $$
        BEGIN
            RAISE EXCEPTION 'quarantine remediation history is append-only';
        END;
        $$ LANGUAGE plpgsql
        """
    )
    existing_trigger = bind.execute(
        sa.text(
            """
            SELECT EXISTS (
                SELECT 1 FROM pg_trigger
                WHERE tgname = 'trg_quarantine_remediations_immutable'
                  AND tgrelid = 'quarantine_remediations'::regclass
                  AND NOT tgisinternal
            )
            """
        )
    ).scalar_one()
    if not existing_trigger:
        op.execute(
            "CREATE TRIGGER trg_quarantine_remediations_immutable "
            "BEFORE UPDATE OR DELETE ON quarantine_remediations "
            "FOR EACH ROW EXECUTE FUNCTION pesaguard_reject_quarantine_remediation_mutation()"
        )


def downgrade() -> None:
    bind = op.get_bind()
    dialect = bind.dialect.name
    if bind.execute(sa.text("SELECT COUNT(*) FROM quarantine_remediations")).scalar_one():
        raise RuntimeError(
            "quarantine remediation history is non-empty; archive it before downgrade"
        )
    if dialect == "sqlite":
        op.execute("DROP TRIGGER IF EXISTS trg_quarantine_remediations_immutable_delete")
        op.execute("DROP TRIGGER IF EXISTS trg_quarantine_remediations_immutable_update")
    elif dialect == "postgresql":
        op.execute(
            "DROP TRIGGER IF EXISTS trg_quarantine_remediations_immutable "
            "ON quarantine_remediations"
        )
        op.execute(
            "DROP FUNCTION IF EXISTS pesaguard_reject_quarantine_remediation_mutation()"
        )
    else:
        raise RuntimeError(
            f"quarantine remediation history is unsupported for database dialect {dialect}"
        )
    op.drop_index(
        "ix_quarantine_remediations_scope_history",
        table_name="quarantine_remediations",
    )
    op.drop_table("quarantine_remediations")
    op.drop_index(
        "uq_quarantine_records_id_tenant",
        table_name="quarantine_records",
    )
    op.drop_index(
        "uq_quarantine_records_deduplication_key",
        table_name="quarantine_records",
    )
    op.drop_column("quarantine_records", "deduplication_key")
    op.drop_column("transaction_provenances", "dq_checks")
    op.drop_column("transaction_provenances", "dq_overall_score")
    op.drop_index(
        "ix_transaction_tenant_provider_currency_created",
        table_name="transactions",
    )
