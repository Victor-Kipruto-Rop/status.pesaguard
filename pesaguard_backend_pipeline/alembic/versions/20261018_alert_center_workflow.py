"""Persist alert lifecycle, deduplication history, and channel delivery state."""

from alembic import op
import sqlalchemy as sa


revision = "20261018_alert_center_workflow"
down_revision = "20261018_transaction_ingestion_runtime_grants"
branch_labels = None
depends_on = None


def _columns(table_name: str) -> set[str]:
    return {column["name"] for column in sa.inspect(op.get_bind()).get_columns(table_name)}


def upgrade() -> None:
    tables = set(sa.inspect(op.get_bind()).get_table_names())
    if "alerts" not in tables:
        op.create_table(
            "alerts",
            sa.Column("id", sa.String(), primary_key=True),
            sa.Column("tenant_id", sa.String(), nullable=False),
            sa.Column("alert_type", sa.String(length=64), nullable=False),
            sa.Column("severity", sa.String(length=16), nullable=False),
            sa.Column("status", sa.String(length=16), nullable=False, server_default="open"),
            sa.Column("title", sa.String(length=255), nullable=False),
            sa.Column("message", sa.Text(), nullable=True),
            sa.Column("source", sa.String(length=64), nullable=True),
            sa.Column("transaction_id", sa.String(), nullable=True),
            sa.Column("discrepancy_id", sa.String(), nullable=True),
            sa.Column("acknowledged_at", sa.DateTime(timezone=True), nullable=True),
            sa.Column("acknowledged_by", sa.String(length=128), nullable=True),
            sa.Column("assigned_to", sa.String(length=128), nullable=True),
            sa.Column("investigation_notes", sa.Text(), nullable=True),
            sa.Column("resolution_note", sa.Text(), nullable=True),
            sa.Column("resolution_outcome", sa.String(length=64), nullable=True),
            sa.Column("resolved_at", sa.DateTime(timezone=True), nullable=True),
            sa.Column("fingerprint", sa.String(length=64), nullable=True),
            sa.Column("duplicate_count", sa.Integer(), nullable=False, server_default="0"),
            sa.Column("last_seen_at", sa.DateTime(timezone=True), nullable=True),
            sa.Column("request_id", sa.String(length=128), nullable=True),
            sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.text("CURRENT_TIMESTAMP")),
            sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.text("CURRENT_TIMESTAMP")),
            sa.UniqueConstraint("tenant_id", "id", name="uq_alert_tenant_id"),
            sa.CheckConstraint("tenant_id IS NOT NULL AND tenant_id <> ''", name="ck_alerts_tenant_id_nonempty"),
            sa.CheckConstraint("severity IN ('info', 'warning', 'critical')", name="ck_alerts_severity"),
            sa.CheckConstraint("status IN ('open', 'acknowledged', 'resolved', 'suppressed')", name="ck_alerts_status"),
        )
    else:
        columns = _columns("alerts")
        additions = (
            ("assigned_to", sa.String(length=128)),
            ("investigation_notes", sa.Text()),
            ("resolution_note", sa.Text()),
            ("resolution_outcome", sa.String(length=64)),
            ("fingerprint", sa.String(length=64)),
            ("duplicate_count", sa.Integer(),),
            ("last_seen_at", sa.DateTime(timezone=True)),
            ("request_id", sa.String(length=128)),
        )
        for item in additions:
            name, column_type = item[0], item[1]
            if name not in columns:
                options = {"nullable": name != "duplicate_count"}
                if name == "duplicate_count":
                    options["server_default"] = "0"
                op.add_column("alerts", sa.Column(name, column_type, **options))

    indexes = {index["name"] for index in sa.inspect(op.get_bind()).get_indexes("alerts")}
    if "uq_alert_tenant_fingerprint" not in indexes:
        op.create_index("uq_alert_tenant_fingerprint", "alerts", ["tenant_id", "fingerprint"], unique=True)
    for name, columns in (
        ("ix_alerts_tenant_severity", ["tenant_id", "severity", "created_at"]),
        ("ix_alerts_tenant_status", ["tenant_id", "status"]),
        ("ix_alerts_tenant_transaction", ["tenant_id", "transaction_id"]),
        ("ix_alerts_tenant_assignee_status", ["tenant_id", "assigned_to", "status"]),
    ):
        if name not in indexes:
            op.create_index(name, "alerts", columns)

    op.create_table(
        "alert_events",
        sa.Column("id", sa.String(length=64), primary_key=True),
        sa.Column("tenant_id", sa.String(length=128), nullable=False),
        sa.Column("alert_id", sa.String(length=128), nullable=False),
        sa.Column("event_key", sa.String(length=255), nullable=False),
        sa.Column("event_type", sa.String(length=64), nullable=False),
        sa.Column("actor", sa.String(length=128), nullable=False),
        sa.Column("from_status", sa.String(length=16), nullable=True),
        sa.Column("to_status", sa.String(length=16), nullable=True),
        sa.Column("details", sa.JSON(), nullable=False, server_default=sa.text("'{}'")),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.text("CURRENT_TIMESTAMP")),
        sa.UniqueConstraint("tenant_id", "event_key", name="uq_alert_event_tenant_key"),
        sa.CheckConstraint("tenant_id IS NOT NULL AND tenant_id <> ''", name="ck_alert_events_tenant_nonempty"),
    )
    op.create_index("ix_alert_events_tenant_alert_created", "alert_events", ["tenant_id", "alert_id", "created_at"])

    op.create_table(
        "alert_deliveries",
        sa.Column("id", sa.String(length=64), primary_key=True),
        sa.Column("tenant_id", sa.String(length=128), nullable=False),
        sa.Column("alert_id", sa.String(length=128), nullable=False),
        sa.Column("channel", sa.String(length=32), nullable=False),
        sa.Column("idempotency_key", sa.String(length=255), nullable=False),
        sa.Column("destination_hash", sa.String(length=64), nullable=True),
        sa.Column("communication_notification_id", sa.String(length=64), nullable=True),
        sa.Column("status", sa.String(length=32), nullable=False, server_default="queued"),
        sa.Column("attempt_count", sa.Integer(), nullable=False, server_default="0"),
            sa.Column("retry_generation", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("error_code", sa.String(length=64), nullable=True),
        sa.Column("last_error", sa.Text(), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.text("CURRENT_TIMESTAMP")),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.text("CURRENT_TIMESTAMP")),
        sa.UniqueConstraint("tenant_id", "idempotency_key", name="uq_alert_delivery_tenant_idempotency"),
        sa.CheckConstraint("tenant_id IS NOT NULL AND tenant_id <> ''", name="ck_alert_deliveries_tenant_nonempty"),
        sa.CheckConstraint("retry_generation >= 0", name="ck_alert_deliveries_retry_generation_nonnegative"),
        sa.CheckConstraint(
            "status IN ('available', 'queued', 'processing', 'accepted', 'sent', 'delivered', 'retrying', 'failed', 'dead_letter', 'not_configured')",
            name="ck_alert_deliveries_status",
        ),
    )
    op.create_index("ix_alert_deliveries_tenant_alert", "alert_deliveries", ["tenant_id", "alert_id", "channel"])

    dialect = op.get_bind().dialect.name
    if dialect == "postgresql":
        op.execute("""
            CREATE OR REPLACE FUNCTION pesaguard_reject_alert_event_mutation()
            RETURNS trigger AS $$
            BEGIN
                RAISE EXCEPTION 'alert event history is append-only';
            END;
            $$ LANGUAGE plpgsql
        """)
        op.execute("""
            CREATE TRIGGER alert_events_append_only
            BEFORE UPDATE OR DELETE ON alert_events
            FOR EACH ROW EXECUTE FUNCTION pesaguard_reject_alert_event_mutation()
        """)
    elif dialect == "sqlite":
        op.execute("""
            CREATE TRIGGER alert_events_reject_update
            BEFORE UPDATE ON alert_events
            BEGIN SELECT RAISE(ABORT, 'alert event history is append-only'); END
        """)
        op.execute("""
            CREATE TRIGGER alert_events_reject_delete
            BEFORE DELETE ON alert_events
            BEGIN SELECT RAISE(ABORT, 'alert event history is append-only'); END
        """)


def downgrade() -> None:
    # Alert events are operational evidence. Keep the schema and its history on
    # application rollback; a later retention migration can remove it safely.
    pass
