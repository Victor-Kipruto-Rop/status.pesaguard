"""Add double-entry ledger tables (ledgers, accounts, transactions, entries)."""

import os

from alembic import op
import sqlalchemy as sa


revision = "20261016_double_entry_ledger"
down_revision = "20261015_workflow_orchestration"
branch_labels = None
depends_on = None


_LEDGER_TABLES = (
    "ledgers",
    "ledger_accounts",
    "ledger_transactions",
    "ledger_entries",
)


def _enable_tenant_rls(bind: sa.engine.Connection) -> None:
    if bind.dialect.name != "postgresql":
        return

    role_name = os.getenv("PESAGUARD_APPLICATION_DB_ROLE", "").strip()
    if not role_name:
        raise RuntimeError(
            "PESAGUARD_APPLICATION_DB_ROLE must name the runtime role "
            "that reads and writes ledger records"
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

    preparer = bind.dialect.identifier_preparer
    quoted_role = preparer.quote(role_name)
    tenant_predicate = "tenant_id = NULLIF(current_setting('app.tenant_id', true), '')"
    for table_name in _LEDGER_TABLES:
        table = preparer.quote(table_name)
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
        op.execute(f"GRANT SELECT, INSERT, UPDATE ON {table} TO {quoted_role}")


def upgrade() -> None:
    op.create_table(
        "ledgers",
        sa.Column("id", sa.String(), nullable=False),
        sa.Column("tenant_id", sa.String(), nullable=False),
        sa.Column("slug", sa.String(length=80), nullable=False),
        sa.Column("name", sa.String(length=120), nullable=False),
        sa.Column("description", sa.Text(), nullable=True),
        sa.Column("status", sa.String(length=16), nullable=False, server_default="active"),
        sa.Column("version", sa.Integer(), nullable=False, server_default="1"),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("tenant_id", "slug", name="uq_ledgers_tenant_slug"),
        sa.UniqueConstraint("tenant_id", "id", name="uq_ledgers_tenant_id"),
        sa.CheckConstraint("tenant_id IS NOT NULL AND tenant_id <> ''", name="ck_ledgers_tenant_id_nonempty"),
        sa.CheckConstraint("status IN ('active', 'archived')", name="ck_ledgers_status"),
    )
    op.create_index("ix_ledgers_tenant_status", "ledgers", ["tenant_id", "status"])

    op.create_table(
        "ledger_accounts",
        sa.Column("id", sa.String(), nullable=False),
        sa.Column("tenant_id", sa.String(), nullable=False),
        sa.Column("ledger_id", sa.String(), nullable=False),
        sa.Column("code", sa.String(length=80), nullable=False),
        sa.Column("name", sa.String(length=120), nullable=False),
        sa.Column("currency", sa.String(length=3), nullable=False, server_default="KES"),
        sa.Column("normal_balance", sa.String(length=8), nullable=False, server_default="debit"),
        sa.Column("status", sa.String(length=16), nullable=False, server_default="active"),
        sa.Column("lock_version", sa.Integer(), nullable=False, server_default="1"),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
        sa.ForeignKeyConstraint(
            ["ledger_id", "tenant_id"],
            ["ledgers.id", "ledgers.tenant_id"],
            name="fk_ledger_accounts_ledger_tenant",
            ondelete="RESTRICT",
        ),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("tenant_id", "ledger_id", "code", name="uq_ledger_accounts_ledger_code"),
        sa.UniqueConstraint("tenant_id", "id", name="uq_ledger_accounts_tenant_id"),
        sa.UniqueConstraint("tenant_id", "ledger_id", "id", name="uq_ledger_accounts_ledger_id"),
        sa.CheckConstraint("tenant_id IS NOT NULL AND tenant_id <> ''", name="ck_ledger_accounts_tenant_id_nonempty"),
        sa.CheckConstraint("status IN ('active', 'suspended', 'closed')", name="ck_ledger_accounts_status"),
        sa.CheckConstraint("normal_balance IN ('debit', 'credit')", name="ck_ledger_accounts_normal_balance"),
        sa.CheckConstraint("length(currency) = 3 AND currency = upper(currency)", name="ck_ledger_accounts_currency_iso"),
        sa.CheckConstraint("lock_version >= 1", name="ck_ledger_accounts_lock_version_positive"),
    )
    op.create_index("ix_ledger_accounts_scope", "ledger_accounts", ["tenant_id", "ledger_id", "status"])

    op.create_table(
        "ledger_transactions",
        sa.Column("id", sa.String(), nullable=False),
        sa.Column("tenant_id", sa.String(), nullable=False),
        sa.Column("ledger_id", sa.String(), nullable=False),
        sa.Column("external_id", sa.String(length=180), nullable=False),
        sa.Column("description", sa.Text(), nullable=True),
        sa.Column("status", sa.String(length=16), nullable=False, server_default="pending"),
        sa.Column("effective_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("posted_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("version", sa.Integer(), nullable=False, server_default="1"),
        sa.Column("metadata", sa.JSON(), nullable=False, server_default="{}"),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.ForeignKeyConstraint(
            ["ledger_id", "tenant_id"],
            ["ledgers.id", "ledgers.tenant_id"],
            name="fk_ledger_tx_ledger_tenant",
            ondelete="RESTRICT",
        ),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("tenant_id", "ledger_id", "external_id", name="uq_ledger_tx_ledger_external"),
        sa.UniqueConstraint("tenant_id", "id", name="uq_ledger_tx_tenant_id"),
        sa.UniqueConstraint("tenant_id", "ledger_id", "id", name="uq_ledger_tx_ledger_id"),
        sa.CheckConstraint("tenant_id IS NOT NULL AND tenant_id <> ''", name="ck_ledger_tx_tenant_id_nonempty"),
        sa.CheckConstraint("status IN ('pending', 'posted', 'voided')", name="ck_ledger_tx_status"),
        sa.CheckConstraint("version >= 1", name="ck_ledger_tx_version_positive"),
    )
    op.create_index("ix_ledger_tx_scope_status", "ledger_transactions", ["tenant_id", "ledger_id", "status", "effective_at"])

    op.create_table(
        "ledger_entries",
        sa.Column("id", sa.String(), nullable=False),
        sa.Column("tenant_id", sa.String(), nullable=False),
        sa.Column("ledger_id", sa.String(), nullable=False),
        sa.Column("ledger_transaction_id", sa.String(), nullable=False),
        sa.Column("ledger_account_id", sa.String(), nullable=False),
        sa.Column("direction", sa.String(length=8), nullable=False),
        sa.Column("amount_minor", sa.Integer(), nullable=False),
        sa.Column("currency", sa.String(length=3), nullable=False, server_default="KES"),
        sa.Column("memo", sa.Text(), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.ForeignKeyConstraint(
            ["tenant_id", "ledger_id", "ledger_transaction_id"],
            ["ledger_transactions.tenant_id", "ledger_transactions.ledger_id", "ledger_transactions.id"],
            name="fk_ledger_entries_tx_ledger_tenant",
            ondelete="RESTRICT",
        ),
        sa.ForeignKeyConstraint(
            ["tenant_id", "ledger_id", "ledger_account_id"],
            ["ledger_accounts.tenant_id", "ledger_accounts.ledger_id", "ledger_accounts.id"],
            name="fk_ledger_entries_account_ledger_tenant",
            ondelete="RESTRICT",
        ),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("tenant_id", "id", name="uq_ledger_entries_tenant_id"),
        sa.CheckConstraint("tenant_id IS NOT NULL AND tenant_id <> ''", name="ck_ledger_entries_tenant_id_nonempty"),
        sa.CheckConstraint("direction IN ('debit', 'credit')", name="ck_ledger_entries_direction"),
        sa.CheckConstraint("amount_minor > 0", name="ck_ledger_entries_amount_positive"),
        sa.CheckConstraint("length(currency) = 3 AND currency = upper(currency)", name="ck_ledger_entries_currency_iso"),
    )
    op.create_index("ix_ledger_entries_tx", "ledger_entries", ["tenant_id", "ledger_transaction_id"])
    op.create_index("ix_ledger_entries_account", "ledger_entries", ["tenant_id", "ledger_account_id"])

    _enable_tenant_rls(op.get_bind())


def downgrade() -> None:
    op.drop_table("ledger_entries")
    op.drop_table("ledger_transactions")
    op.drop_table("ledger_accounts")
    op.drop_table("ledgers")
