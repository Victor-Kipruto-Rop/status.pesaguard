"""Add settlement optimistic locking and append-only audit history."""

from alembic import op
import sqlalchemy as sa


revision = "20261014_settlement_integrity"
down_revision = "20261014_quarantine_remediation_history"
branch_labels = None
depends_on = None


def upgrade() -> None:
    with op.batch_alter_table("settlements") as batch:
        batch.add_column(
            sa.Column("version", sa.Integer(), nullable=False, server_default="1")
        )
        batch.create_check_constraint("ck_settlements_version_positive", "version >= 1")
        batch.create_unique_constraint(
            "uq_settlements_id_tenant", ["id", "tenant_id"]
        )

    op.create_table(
        "settlement_audit_events",
        sa.Column("id", sa.String(), nullable=False),
        sa.Column("tenant_id", sa.String(), nullable=False),
        sa.Column("settlement_id", sa.String(), nullable=False),
        sa.Column("event_type", sa.String(length=32), nullable=False),
        sa.Column("from_status", sa.String(length=32), nullable=True),
        sa.Column("to_status", sa.String(length=32), nullable=True),
        sa.Column("details", sa.JSON(), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.CheckConstraint(
            "tenant_id IS NOT NULL AND tenant_id <> ''",
            name="ck_settlement_audit_events_tenant_id_nonempty",
        ),
        sa.CheckConstraint(
            "event_type IN ('calculated', 'reconciled', 'status_transition', "
            "'transition_rejected')",
            name="ck_settlement_audit_events_type",
        ),
        sa.ForeignKeyConstraint(
            ["settlement_id", "tenant_id"],
            ["settlements.id", "settlements.tenant_id"],
            name="fk_settlement_audit_events_settlement_tenant",
            ondelete="RESTRICT",
        ),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index(
        "ix_settlement_audit_events_scope_history",
        "settlement_audit_events",
        ["tenant_id", "settlement_id", "created_at"],
        unique=False,
    )

    dialect = op.get_bind().dialect.name
    if dialect == "sqlite":
        op.execute(
            """
            CREATE TRIGGER trg_settlements_lifecycle_and_audit
            AFTER UPDATE OF status ON settlements
            WHEN NEW.status IS NOT OLD.status
            BEGIN
                SELECT CASE WHEN NOT (
                    (OLD.status = 'pending' AND NEW.status IN ('calculating', 'failed'))
                    OR (OLD.status = 'calculating' AND NEW.status IN ('calculated', 'failed'))
                    OR (OLD.status = 'calculated'
                        AND NEW.status IN ('reconciling', 'failed', 'disputed'))
                    OR (OLD.status = 'reconciling'
                        AND NEW.status IN ('reconciled', 'failed', 'disputed'))
                    OR (OLD.status = 'reconciled' AND NEW.status IN ('settled', 'disputed'))
                    OR (OLD.status = 'settled' AND NEW.status = 'disputed')
                    OR (OLD.status = 'disputed' AND NEW.status = 'settled')
                ) THEN RAISE(ABORT, 'invalid settlement lifecycle transition') END;
                INSERT INTO settlement_audit_events (
                    id, tenant_id, settlement_id, event_type,
                    from_status, to_status, details, created_at
                ) VALUES (
                    'sta_' || lower(hex(randomblob(16))),
                    NEW.tenant_id,
                    NEW.id,
                    'status_transition',
                    OLD.status,
                    NEW.status,
                    json_object(
                        'meaning',
                        CASE WHEN NEW.status = 'settled'
                            THEN 'internal_record_finalized_only'
                            ELSE 'internal_settlement_state'
                        END
                    ),
                    CURRENT_TIMESTAMP
                );
            END
            """
        )
        op.execute(
            "CREATE TRIGGER trg_settlement_audit_events_immutable_update "
            "BEFORE UPDATE ON settlement_audit_events "
            "BEGIN SELECT RAISE(ABORT, 'settlement audit history is append-only'); END"
        )
        op.execute(
            "CREATE TRIGGER trg_settlement_audit_events_immutable_delete "
            "BEFORE DELETE ON settlement_audit_events "
            "BEGIN SELECT RAISE(ABORT, 'settlement audit history is append-only'); END"
        )
        return
    if dialect != "postgresql":
        raise RuntimeError(
            f"settlement integrity is unsupported for database dialect {dialect}"
        )

    op.execute(
        """
        CREATE FUNCTION pesaguard_reject_settlement_audit_mutation()
        RETURNS trigger AS $$
        BEGIN
            RAISE EXCEPTION 'settlement audit history is append-only';
        END;
        $$ LANGUAGE plpgsql
        """
    )
    op.execute(
        """
        CREATE FUNCTION pesaguard_audit_settlement_status_transition()
        RETURNS trigger AS $$
        BEGIN
            IF NOT (
                (OLD.status = 'pending' AND NEW.status IN ('calculating', 'failed'))
                OR (OLD.status = 'calculating' AND NEW.status IN ('calculated', 'failed'))
                OR (OLD.status = 'calculated'
                    AND NEW.status IN ('reconciling', 'failed', 'disputed'))
                OR (OLD.status = 'reconciling'
                    AND NEW.status IN ('reconciled', 'failed', 'disputed'))
                OR (OLD.status = 'reconciled' AND NEW.status IN ('settled', 'disputed'))
                OR (OLD.status = 'settled' AND NEW.status = 'disputed')
                OR (OLD.status = 'disputed' AND NEW.status = 'settled')
            ) THEN
                RAISE EXCEPTION 'invalid settlement lifecycle transition';
            END IF;

            INSERT INTO settlement_audit_events (
                id, tenant_id, settlement_id, event_type,
                from_status, to_status, details, created_at
            ) VALUES (
                'sta_' || md5(random()::text || clock_timestamp()::text),
                NEW.tenant_id,
                NEW.id,
                'status_transition',
                OLD.status,
                NEW.status,
                json_build_object(
                    'meaning',
                    CASE WHEN NEW.status = 'settled'
                        THEN 'internal_record_finalized_only'
                        ELSE 'internal_settlement_state'
                    END
                ),
                CURRENT_TIMESTAMP
            );
            RETURN NEW;
        END;
        $$ LANGUAGE plpgsql
        """
    )
    op.execute(
        "CREATE TRIGGER trg_settlements_lifecycle_and_audit "
        "AFTER UPDATE OF status ON settlements "
        "FOR EACH ROW WHEN (OLD.status IS DISTINCT FROM NEW.status) "
        "EXECUTE FUNCTION pesaguard_audit_settlement_status_transition()"
    )
    op.execute(
        "CREATE TRIGGER trg_settlement_audit_events_immutable "
        "BEFORE UPDATE OR DELETE ON settlement_audit_events "
        "FOR EACH ROW EXECUTE FUNCTION pesaguard_reject_settlement_audit_mutation()"
    )


def downgrade() -> None:
    dialect = op.get_bind().dialect.name
    audit_count = op.get_bind().execute(
        sa.text("SELECT COUNT(*) FROM settlement_audit_events")
    ).scalar_one()
    if audit_count:
        raise RuntimeError(
            "cannot downgrade settlement integrity while append-only settlement "
            "audit history exists"
        )

    if dialect == "sqlite":
        op.execute("DROP TRIGGER IF EXISTS trg_settlements_lifecycle_and_audit")
        op.execute("DROP TRIGGER IF EXISTS trg_settlement_audit_events_immutable_delete")
        op.execute("DROP TRIGGER IF EXISTS trg_settlement_audit_events_immutable_update")
    elif dialect == "postgresql":
        op.execute(
            "DROP TRIGGER IF EXISTS trg_settlements_lifecycle_and_audit ON settlements"
        )
        op.execute(
            "DROP FUNCTION IF EXISTS pesaguard_audit_settlement_status_transition()"
        )
        op.execute(
            "DROP TRIGGER IF EXISTS trg_settlement_audit_events_immutable "
            "ON settlement_audit_events"
        )
        op.execute(
            "DROP FUNCTION IF EXISTS pesaguard_reject_settlement_audit_mutation()"
        )
    else:
        raise RuntimeError(
            f"settlement integrity is unsupported for database dialect {dialect}"
        )

    op.drop_index(
        "ix_settlement_audit_events_scope_history",
        table_name="settlement_audit_events",
    )
    op.drop_table("settlement_audit_events")
    with op.batch_alter_table("settlements") as batch:
        batch.drop_constraint("uq_settlements_id_tenant", type_="unique")
        batch.drop_constraint("ck_settlements_version_positive", type_="check")
        batch.drop_column("version")
