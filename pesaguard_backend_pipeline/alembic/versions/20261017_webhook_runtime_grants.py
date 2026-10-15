"""Grant the runtime role only the webhook table operations in use."""

from __future__ import annotations

import os

from alembic import op
import sqlalchemy as sa


revision = "20261017_webhook_runtime_grants"
down_revision = "20261016_double_entry_ledger"
branch_labels = None
depends_on = None


_WEBHOOK_PRIVILEGES = {
    "webhook_configs": ("SELECT", "INSERT", "UPDATE", "DELETE"),
    "webhook_deliveries": ("SELECT", "INSERT", "UPDATE"),
}


def _application_role(bind: sa.engine.Connection) -> str:
    role_name = os.getenv("PESAGUARD_APPLICATION_DB_ROLE", "").strip()
    if not role_name:
        raise RuntimeError(
            "PESAGUARD_APPLICATION_DB_ROLE must name the webhook runtime role"
        )
    role = bind.execute(
        sa.text(
            "SELECT rolsuper, rolbypassrls "
            "FROM pg_roles WHERE rolname = :role_name"
        ),
        {"role_name": role_name},
    ).one_or_none()
    if role is None or role[0] or role[1]:
        raise RuntimeError(
            "PESAGUARD_APPLICATION_DB_ROLE must exist and be a "
            "non-superuser, non-BYPASSRLS role"
        )
    return bind.dialect.identifier_preparer.quote(role_name)


def upgrade() -> None:
    bind = op.get_bind()
    if bind.dialect.name != "postgresql":
        return

    role = _application_role(bind)
    tables = set(sa.inspect(bind).get_table_names())
    missing = set(_WEBHOOK_PRIVILEGES) - tables
    if missing:
        raise RuntimeError(
            "Webhook runtime grants require tables that are missing: "
            + ", ".join(sorted(missing))
        )

    preparer = bind.dialect.identifier_preparer
    for table_name, privileges in _WEBHOOK_PRIVILEGES.items():
        table = preparer.quote(table_name)
        op.execute(f"GRANT {', '.join(privileges)} ON {table} TO {role}")


def downgrade() -> None:
    bind = op.get_bind()
    if bind.dialect.name != "postgresql":
        return

    role_name = os.getenv("PESAGUARD_APPLICATION_DB_ROLE", "").strip()
    if not role_name:
        raise RuntimeError(
            "PESAGUARD_APPLICATION_DB_ROLE must name the webhook runtime role"
        )
    role = bind.dialect.identifier_preparer.quote(role_name)
    preparer = bind.dialect.identifier_preparer
    for table_name, privileges in _WEBHOOK_PRIVILEGES.items():
        table = preparer.quote(table_name)
        op.execute(f"REVOKE {', '.join(privileges)} ON {table} FROM {role}")
