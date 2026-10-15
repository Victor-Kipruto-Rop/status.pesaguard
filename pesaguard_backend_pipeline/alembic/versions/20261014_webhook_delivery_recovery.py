"""Create webhook delivery storage and add durable recovery lookup state."""

from alembic import op
import sqlalchemy as sa


revision = "20261014_webhook_delivery_recovery"
down_revision = "20261014_tenant_row_level_security"
branch_labels = None
depends_on = None


def upgrade() -> None:
    bind = op.get_bind()
    inspector = sa.inspect(bind)
    if not inspector.has_table("webhook_deliveries"):
        op.create_table(
            "webhook_deliveries",
            sa.Column("id", sa.String(), primary_key=True),
            sa.Column(
                "tenant_id",
                sa.String(),
                nullable=False,
                server_default="default",
            ),
            sa.Column("webhook_id", sa.String(), nullable=False),
            sa.Column("event_type", sa.String(), nullable=False),
            sa.Column("payload", sa.JSON(), nullable=False),
            sa.Column("status", sa.String(), nullable=False),
            sa.Column("response_status", sa.Integer(), nullable=True),
            sa.Column("response_body", sa.Text(), nullable=True),
            sa.Column("attempt_count", sa.Integer(), nullable=True),
            sa.Column(
                "created_at",
                sa.DateTime(timezone=True),
                nullable=False,
                server_default=sa.func.now(),
            ),
            sa.Column(
                "updated_at",
                sa.DateTime(timezone=True),
                nullable=False,
                server_default=sa.func.now(),
            ),
            sa.Column("delivered_at", sa.DateTime(timezone=True), nullable=True),
            sa.CheckConstraint(
                "tenant_id IS NOT NULL AND tenant_id <> ''",
                name="ck_webhook_deliveries_tenant_id_nonempty",
            ),
            sa.Index("ix_webhook_deliveries_webhook_id", "webhook_id"),
            sa.Index(
                "ix_webhook_deliveries_tenant_webhook",
                "tenant_id",
                "webhook_id",
            ),
            sa.Index("ix_webhook_deliveries_created_at", "created_at"),
            sa.Index(
                "ix_webhook_deliveries_tenant_created",
                "tenant_id",
                "created_at",
            ),
            sa.Index(
                "ix_webhook_deliveries_recovery",
                "status",
                "updated_at",
            ),
        )
    else:
        columns = {
            column["name"] for column in inspector.get_columns("webhook_deliveries")
        }
        if "updated_at" not in columns:
            op.add_column(
                "webhook_deliveries",
                sa.Column(
                    "updated_at",
                    sa.DateTime(timezone=True),
                    nullable=False,
                    server_default=sa.func.now(),
                ),
            )

        indexes = {
            index["name"] for index in inspector.get_indexes("webhook_deliveries")
        }
        if "ix_webhook_deliveries_recovery" not in indexes:
            op.create_index(
                "ix_webhook_deliveries_recovery",
                "webhook_deliveries",
                ["status", "updated_at"],
            )

    if bind.dialect.name == "postgresql":
        preparer = bind.dialect.identifier_preparer
        table = preparer.quote("webhook_deliveries")
        policies = {
            row[0]
            for row in bind.execute(
                sa.text(
                    "SELECT policyname FROM pg_policies "
                    "WHERE schemaname = current_schema() "
                    "AND tablename = 'webhook_deliveries'"
                )
            )
        }
        tenant_predicate = (
            "tenant_id = NULLIF(current_setting('app.tenant_id', true), '')"
        )
        op.execute(f"ALTER TABLE {table} ENABLE ROW LEVEL SECURITY")
        op.execute(f"ALTER TABLE {table} FORCE ROW LEVEL SECURITY")
        if "pesaguard_tenant_isolation" not in policies:
            op.execute(
                f"CREATE POLICY pesaguard_tenant_isolation ON {table} "
                f"USING ({tenant_predicate}) WITH CHECK ({tenant_predicate})"
            )
        if "pesaguard_tenant_guard" not in policies:
            op.execute(
                f"CREATE POLICY pesaguard_tenant_guard ON {table} "
                f"AS RESTRICTIVE USING ({tenant_predicate}) "
                f"WITH CHECK ({tenant_predicate})"
            )


def downgrade() -> None:
    bind = op.get_bind()
    inspector = sa.inspect(bind)
    if not inspector.has_table("webhook_deliveries"):
        return

    indexes = {
        index["name"] for index in inspector.get_indexes("webhook_deliveries")
    }
    if "ix_webhook_deliveries_recovery" in indexes:
        op.drop_index(
            "ix_webhook_deliveries_recovery",
            table_name="webhook_deliveries",
        )

    columns = {
        column["name"] for column in inspector.get_columns("webhook_deliveries")
    }
    if "updated_at" in columns:
        op.drop_column("webhook_deliveries", "updated_at")
