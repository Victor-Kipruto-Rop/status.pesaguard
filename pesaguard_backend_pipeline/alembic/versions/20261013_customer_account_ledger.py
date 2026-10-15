# --- customer / account ledger ---------------------------------------------------------

from alembic import op
import sqlalchemy as sa


revision = "20261013_customer_account_ledger"
down_revision = "20261013_currency_minor_units"
branch_labels = None
depends_on = None


def upgrade() -> None:
    # Customers: one row per tenant+derived key, keyed by the tokenized identity.
    op.create_table(
        "customers",
        sa.Column("id", sa.String(), nullable=False),
        sa.Column("tenant_id", sa.String(), nullable=False),
        sa.Column("customer_key", sa.String(length=128), nullable=False),
        sa.Column("internal_ref", sa.String(length=255), nullable=False),
        sa.Column("phone_number", sa.String(length=16), nullable=False),
        sa.Column("label", sa.String(length=64), nullable=False),
        sa.Column(
            "status",
            sa.String(length=16),
            nullable=False,
            server_default="active",
            doc="active | suspended | closed",
        ),
        sa.Column(
            "total_volumes",
            sa.JSON,
            nullable=False,
            server_default="{}",
            doc="canonical currency -> total amount",
        ),
        sa.Column("first_seen", sa.DateTime(timezone=True), nullable=False),
        sa.Column("last_seen", sa.DateTime(timezone=True), nullable=False),
        sa.Column("transaction_count", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("total_amount", sa.Numeric(19, 3), nullable=False, server_default="0"),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("tenant_id", "customer_key", name="uq_customers_tenant_key"),
        sa.CheckConstraint("tenant_id IS NOT NULL AND tenant_id <> ''", name="ck_customers_tenant_id_nonempty"),
        sa.CheckConstraint("status IN ('active', 'suspended', 'closed')", name="ck_customers_status"),
    )
    op.create_index("ix_customers_tenant_status", "customers", ["tenant_id", "status"])
    op.create_index("ix_customers_tenant_key", "customers", ["tenant_id", "customer_key"])

    # Customer accounts (one account per customer x currency).
    op.create_table(
        "customer_accounts",
        sa.Column("id", sa.String(), nullable=False),
        sa.Column("tenant_id", sa.String(), nullable=False),
        sa.Column("customer_key", sa.String(length=128), nullable=False),
        sa.Column("currency", sa.String(length=3), nullable=False, server_default="KES"),
        sa.Column("status", sa.String(length=16), nullable=False, server_default="active"),
        sa.Column("total", sa.Numeric(19, 3), nullable=False, server_default="0"),
        sa.Column("transaction_count", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("first_seen", sa.DateTime(timezone=True), nullable=False),
        sa.Column("last_seen", sa.DateTime(timezone=True), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("internal_ref", sa.String(length=255), nullable=False),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("tenant_id", "customer_key", "currency", name="uq_customer_accounts_tenant_key_currency"),
        sa.CheckConstraint("tenant_id IS NOT NULL AND tenant_id <> ''", name="ck_customer_accounts_tenant_id_nonempty"),
        sa.CheckConstraint("length(currency) = 3 AND currency = upper(currency)", name="ck_customer_accounts_currency_iso"),
        sa.CheckConstraint("status IN ('active', 'suspended', 'closed')", name="ck_customer_accounts_status"),
    )
    op.create_index("ix_customer_accounts_tenant_key", "customer_accounts", ["tenant_id", "customer_key"])
    op.create_index("ix_customer_accounts_tenant_currency", "customer_accounts", ["tenant_id", "currency"])

    # Balance rollup: recomputed snapshot per customer x currency (no second book of record).
    op.create_table(
        "account_balances",
        sa.Column("id", sa.String(), nullable=False),
        sa.Column("tenant_id", sa.String(), nullable=False),
        sa.Column("customer_key", sa.String(length=128), nullable=False),
        sa.Column("currency", sa.String(length=3), nullable=False, server_default="KES"),
        sa.Column("total", sa.Numeric(19, 2), nullable=False, server_default="0"),
        sa.Column("transaction_count", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("computed_at", sa.DateTime(timezone=True), nullable=False),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("tenant_id", "customer_key", "currency", name="uq_account_balances_tenant_key_currency"),
        sa.CheckConstraint("tenant_id IS NOT NULL AND tenant_id <> ''", name="ck_account_balances_tenant_id_nonempty"),
        sa.CheckConstraint("length(currency) = 3 AND currency = upper(currency)", name="ck_account_balances_currency_iso"),
        sa.CheckConstraint("total >= 0", name="ck_account_balances_total_nonnegative"),
        sa.CheckConstraint("transaction_count >= 0", name="ck_account_balances_count_nonnegative"),
    )
    op.create_index("ix_account_balances_tenant_currency", "account_balances", ["tenant_id", "currency"])
    op.create_index("ix_account_balances_tenant_key", "account_balances", ["tenant_id", "customer_key"])

    op.create_table(
        "account_relationships",
        sa.Column("id", sa.String(), nullable=False),
        sa.Column("tenant_id", sa.String(), nullable=False),
        sa.Column("customer_key", sa.String(length=128), nullable=False),
        sa.Column("merchant_id", sa.String(length=255), nullable=False),
        sa.Column("status", sa.String(length=16), nullable=False, server_default="active"),
        sa.Column("first_seen", sa.DateTime(timezone=True), nullable=False),
        sa.Column("last_seen", sa.DateTime(timezone=True), nullable=False),
        sa.Column("pair_count", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint(
            "tenant_id",
            "customer_key",
            "merchant_id",
            name="uq_account_relationships_tenant_customer_merchant",
        ),
        sa.CheckConstraint(
            "tenant_id IS NOT NULL AND tenant_id <> ''",
            name="ck_account_relationships_tenant_id_nonempty",
        ),
        sa.CheckConstraint("length(merchant_id) > 0", name="ck_account_relationships_merchant_nonempty"),
        sa.CheckConstraint("status IN ('active', 'closed')", name="ck_account_relationships_status"),
    )
    op.create_index(
        "ix_account_relationships_tenant_customer",
        "account_relationships",
        ["tenant_id", "customer_key"],
    )
    op.create_index(
        "ix_account_relationships_tenant_merchant",
        "account_relationships",
        ["tenant_id", "merchant_id"],
    )


def downgrade() -> None:
    op.drop_index("ix_account_relationships_tenant_merchant", table_name="account_relationships")
    op.drop_index("ix_account_relationships_tenant_customer", table_name="account_relationships")
    op.drop_table("account_relationships")
    op.drop_index("ix_account_balances_tenant_key", table_name="account_balances")
    op.drop_index("ix_account_balances_tenant_currency", table_name="account_balances")
    op.drop_table("account_balances")
    op.drop_index("ix_customer_accounts_tenant_currency", table_name="customer_accounts")
    op.drop_index("ix_customer_accounts_tenant_key", table_name="customer_accounts")
    op.drop_table("customer_accounts")
    op.drop_index("ix_customers_tenant_key", table_name="customers")
    op.drop_index("ix_customers_tenant_status", table_name="customers")
    op.drop_table("customers")
