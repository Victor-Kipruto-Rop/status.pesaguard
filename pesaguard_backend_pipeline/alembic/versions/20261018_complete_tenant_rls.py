"""Catch up RLS and runtime grants for tenant tables added after the baseline."""

from alembic import op
import os
import sqlalchemy as sa


revision = "20261018_complete_tenant_rls"
down_revision = "20261018_alert_center_workflow"
branch_labels = None
depends_on = None


def upgrade() -> None:
    bind = op.get_bind()
    if bind.dialect.name != "postgresql":
        raise RuntimeError("tenant RLS completion requires PostgreSQL")

    inspector = sa.inspect(bind)
    tables = set(inspector.get_table_names(schema="public"))
    preparer = bind.dialect.identifier_preparer
    registry_role = os.getenv("PESAGUARD_TENANT_REGISTRY_DB_ROLE", "").strip()
    lookup_role = os.getenv("PESAGUARD_API_KEY_LOOKUP_DB_ROLE", "").strip()

    for table_name in sorted(tables):
        columns = {
            column["name"]
            for column in inspector.get_columns(table_name, schema="public")
        }
        if "tenant_id" not in columns:
            continue

        table = f"public.{preparer.quote(table_name)}"
        policies = {
            row[0]
            for row in bind.execute(
                sa.text(
                    "SELECT policyname FROM pg_policies "
                    "WHERE schemaname = 'public' AND tablename = :table_name"
                ),
                {"table_name": table_name},
            )
        }
        tenant_predicate = (
            "tenant_id = NULLIF(current_setting('app.tenant_id', true), '')"
        )
        trusted_roles = []
        if table_name == "iam_tenants" and registry_role:
            trusted_roles.append(registry_role)
        if table_name == "api_key_records" and lookup_role:
            trusted_roles.append(lookup_role)
        exceptions = " OR ".join(
            "current_user = '" + role.replace("'", "''") + "'"
            for role in trusted_roles
        )
        restrictive_predicate = (
            f"({tenant_predicate}"
            + (f" OR {exceptions}" if exceptions else "")
            + ")"
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
                f"CREATE POLICY pesaguard_tenant_guard ON {table} AS RESTRICTIVE "
                f"USING ({restrictive_predicate}) "
                f"WITH CHECK ({restrictive_predicate})"
            )

    app_role_name = os.getenv("PESAGUARD_APPLICATION_DB_ROLE", "").strip()
    if not app_role_name:
        raise RuntimeError("PESAGUARD_APPLICATION_DB_ROLE is required for runtime grants")
    app_role = preparer.quote(app_role_name)

    # The runtime role gets only the operations required by these features.
    # Event history is append-only; alert rows and delivery state are mutable.
    grants = {
        "user_devices": "SELECT, INSERT, UPDATE",
        "alerts": "SELECT, INSERT, UPDATE",
        "alert_events": "SELECT, INSERT",
        "alert_deliveries": "SELECT, INSERT, UPDATE",
    }
    for table_name, privileges in grants.items():
        if table_name in tables:
            table = f"public.{preparer.quote(table_name)}"
            op.execute(f"GRANT {privileges} ON TABLE {table} TO {app_role}")

    if "scheduled_jobs" in tables:
        columns = {
            column["name"]
            for column in sa.inspect(bind).get_columns("scheduled_jobs", schema="public")
        }
        if "payload" not in columns:
            op.add_column(
                "scheduled_jobs",
                sa.Column("payload", sa.JSON(), nullable=False, server_default=sa.text("'{}'")),
            )


def downgrade() -> None:
    # Keep forced tenant isolation and the JSON column as safe, backward-
    # compatible schema additions. Roll application code forward on rollback.
    pass
