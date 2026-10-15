"""Support currency-specific minor units without mixing monetary totals."""

from alembic import op
import sqlalchemy as sa


revision = "20261013_currency_minor_units"
down_revision = "20261013_harden_transaction_ingestion"
branch_labels = None
depends_on = None


_MONEY_COLUMNS = {
    "transactions": ("trans_amount",),
    "internal_records": ("amount",),
    "settlements": (
        "gross_amount",
        "fee_amount",
        "net_amount",
        "expected_net_amount",
        "variance_amount",
    ),
    "settlement_lines": ("amount", "fee", "net"),
}


def _tables():
    return set(sa.inspect(op.get_bind()).get_table_names())


def _columns(table):
    return {column["name"] for column in sa.inspect(op.get_bind()).get_columns(table)}


def _widen_money_columns():
    if op.get_bind().dialect.name != "postgresql":
        return
    bind = op.get_bind()
    tables = _tables()
    immutable_trigger = False
    if "transactions" in tables:
        immutable_trigger = bind.execute(
            sa.text(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM pg_trigger
                    WHERE tgname = 'trg_transactions_immutable_source'
                      AND tgrelid = 'transactions'::regclass
                      AND NOT tgisinternal
                )
                """
            )
        ).scalar_one()
    if immutable_trigger:
        op.execute(
            "DROP TRIGGER trg_transactions_immutable_source ON transactions"
        )
    for table, columns in _MONEY_COLUMNS.items():
        if table not in tables:
            continue
        present = _columns(table)
        for column in columns:
            if column in present:
                op.alter_column(
                    table,
                    column,
                    existing_type=sa.Numeric(18, 2),
                    type_=sa.Numeric(19, 3),
                    postgresql_using=f"{column}::numeric(19,3)",
                )
    if "merchant_metrics" in tables:
        for column in ("total_volume", "average_transaction"):
            if column in _columns("merchant_metrics"):
                op.alter_column(
                    "merchant_metrics",
                    column,
                    existing_type=sa.Numeric(20, 2),
                    type_=sa.Numeric(21, 3),
                    postgresql_using=f"{column}::numeric(21,3)",
                )
    if immutable_trigger:
        op.execute(
            """
            CREATE TRIGGER trg_transactions_immutable_source
            BEFORE UPDATE OF raw_payload, trans_id, tenant_id, provider_account_id,
                provider, idempotency_key, external_reference, provider_transaction_id,
                trans_amount, currency, msisdn, business_short_code, trans_time,
                raw_payload_hash, source_schema_version
            ON transactions
            FOR EACH ROW
            EXECUTE FUNCTION pesaguard_reject_transaction_source_mutation()
            """
        )


def upgrade() -> None:
    tables = _tables()
    _widen_money_columns()

    if "internal_records" in tables and "currency" not in _columns("internal_records"):
        with op.batch_alter_table("internal_records") as batch:
            batch.add_column(
                sa.Column("currency", sa.String(length=3), nullable=False, server_default="KES")
            )
            batch.create_check_constraint(
                "ck_internal_records_currency_iso",
                "length(currency) = 3 AND currency = upper(currency)",
            )

    if "merchant_metrics" in tables:
        present = _columns("merchant_metrics")
        unique_constraints = sa.inspect(op.get_bind()).get_unique_constraints("merchant_metrics")
        old_unique = next(
            (
                item.get("name")
                for item in unique_constraints
                if item.get("column_names")
                == ["tenant_id", "merchant_id", "granularity", "bucket_start"]
            ),
            None,
        )
        has_old_unique = any(
            item.get("column_names")
            == ["tenant_id", "merchant_id", "granularity", "bucket_start"]
            for item in unique_constraints
        )
        with op.batch_alter_table(
            "merchant_metrics",
            naming_convention={"uq": "uq_%(table_name)s_%(column_0_name)s"},
        ) as batch:
            if "currency" not in present:
                batch.add_column(
                    sa.Column("currency", sa.String(length=3), nullable=False, server_default="KES")
                )
            if has_old_unique:
                batch.drop_constraint(
                    old_unique or "uq_merchant_metrics_tenant_id",
                    type_="unique",
                )
            if not any(
                item.get("name") == "ck_merchant_metrics_currency_iso"
                for item in sa.inspect(op.get_bind()).get_check_constraints("merchant_metrics")
            ):
                batch.create_check_constraint(
                    "ck_merchant_metrics_currency_iso",
                    "length(currency) = 3 AND currency = upper(currency)",
                )
            if not any(
                item.get("column_names")
                == ["tenant_id", "merchant_id", "currency", "granularity", "bucket_start"]
                for item in unique_constraints
            ):
                batch.create_unique_constraint(
                    "uq_merchant_metric_bucket_currency",
                    ["tenant_id", "merchant_id", "currency", "granularity", "bucket_start"],
                )

    if "settlements" in tables:
        unique_constraints = sa.inspect(op.get_bind()).get_unique_constraints("settlements")
        check_constraints = sa.inspect(op.get_bind()).get_check_constraints("settlements")
        old_unique = next(
            (
                item["name"]
                for item in unique_constraints
                if item.get("column_names") == ["tenant_id", "settlement_key"]
            ),
            None,
        )
        old_currency_check = next(
            (item["name"] for item in check_constraints if "currency <> ''" in item.get("sqltext", "")),
            None,
        )
        with op.batch_alter_table("settlements") as batch:
            batch.alter_column(
                "currency",
                existing_type=sa.String(length=8),
                type_=sa.String(length=3),
            )
            if old_unique:
                batch.drop_constraint(old_unique, type_="unique")
            if old_currency_check:
                batch.drop_constraint(old_currency_check, type_="check")
            batch.create_check_constraint(
                "ck_settlements_currency_iso",
                "length(currency) = 3 AND currency = upper(currency)",
            )
            batch.create_unique_constraint(
                "uq_settlements_tenant_key_currency",
                ["tenant_id", "settlement_key", "currency"],
            )


def downgrade() -> None:
    bind = op.get_bind()
    tables = _tables()
    if bind.dialect.name == "postgresql":
        for table, columns in _MONEY_COLUMNS.items():
            if table not in tables:
                continue
            for column in columns:
                if column in _columns(table) and bind.execute(
                    sa.text(f'SELECT 1 FROM "{table}" WHERE "{column}" IS NOT NULL AND "{column}" <> round("{column}", 2) LIMIT 1')
                ).first():
                    raise RuntimeError(
                        f"cannot downgrade {table}.{column}: values use more than two decimal places"
                    )
        if "merchant_metrics" in tables:
            for column in ("total_volume", "average_transaction"):
                if bind.execute(
                    sa.text(f'SELECT 1 FROM merchant_metrics WHERE "{column}" <> round("{column}", 2) LIMIT 1')
                ).first():
                    raise RuntimeError(
                        f"cannot downgrade merchant_metrics.{column}: values use more than two decimal places"
                    )

    if "internal_records" in tables and "currency" in _columns("internal_records"):
        if bind.execute(
            sa.text("SELECT 1 FROM internal_records WHERE currency <> 'KES' LIMIT 1")
        ).first():
            raise RuntimeError(
                "cannot downgrade internal_records: non-KES currency values would lose their denomination"
            )

    if "merchant_metrics" in tables and "currency" in _columns("merchant_metrics"):
        duplicates = bind.execute(
            sa.text(
                "SELECT 1 FROM merchant_metrics "
                "GROUP BY tenant_id, merchant_id, granularity, bucket_start "
                "HAVING count(*) > 1 LIMIT 1"
            )
        ).first()
        if duplicates:
            raise RuntimeError(
                "cannot downgrade merchant_metrics: multiple currencies share a metric bucket"
            )
        if bind.execute(
            sa.text("SELECT 1 FROM merchant_metrics WHERE currency <> 'KES' LIMIT 1")
        ).first():
            raise RuntimeError(
                "cannot downgrade merchant_metrics: non-KES buckets would lose their denomination"
            )

    if "settlements" in _tables():
        duplicates = bind.execute(
            sa.text(
                "SELECT 1 FROM settlements GROUP BY tenant_id, settlement_key "
                "HAVING count(*) > 1 LIMIT 1"
            )
        ).first()
        if duplicates:
            raise RuntimeError(
                "cannot downgrade settlements: multiple currencies share a tenant settlement key"
            )
        with op.batch_alter_table("settlements") as batch:
            batch.drop_constraint("uq_settlements_tenant_key_currency", type_="unique")
            batch.drop_constraint("ck_settlements_currency_iso", type_="check")
            batch.alter_column(
                "currency",
                existing_type=sa.String(length=3),
                type_=sa.String(length=8),
            )
            batch.create_check_constraint("ck_settlements_currency_nonempty", "currency <> ''")
            batch.create_unique_constraint(
                "uq_settlements_tenant_key", ["tenant_id", "settlement_key"]
            )

    if "merchant_metrics" in _tables():
        with op.batch_alter_table("merchant_metrics") as batch:
            batch.drop_constraint("uq_merchant_metric_bucket_currency", type_="unique")
            batch.drop_constraint("ck_merchant_metrics_currency_iso", type_="check")
            batch.drop_column("currency")
            batch.create_unique_constraint(
                "uq_merchant_metric_bucket",
                ["tenant_id", "merchant_id", "granularity", "bucket_start"],
            )

    if "internal_records" in _tables():
        with op.batch_alter_table("internal_records") as batch:
            batch.drop_constraint("ck_internal_records_currency_iso", type_="check")
            batch.drop_column("currency")

    if bind.dialect.name == "postgresql":
        for table, columns in _MONEY_COLUMNS.items():
            if table not in _tables():
                continue
            for column in columns:
                if column in _columns(table):
                    op.alter_column(
                        table,
                        column,
                        existing_type=sa.Numeric(19, 3),
                        type_=sa.Numeric(18, 2),
                        postgresql_using=f"{column}::numeric(18,2)",
                    )
        if "merchant_metrics" in _tables():
            for column in ("total_volume", "average_transaction"):
                op.alter_column(
                    "merchant_metrics",
                    column,
                    existing_type=sa.Numeric(21, 3),
                    type_=sa.Numeric(20, 2),
                    postgresql_using=f"{column}::numeric(20,2)",
                )
