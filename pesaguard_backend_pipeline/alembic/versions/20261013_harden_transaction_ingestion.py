"""Record ingestion provenance and enforce immutable transaction source data."""

from alembic import op
import sqlalchemy as sa


revision = "20261013_harden_transaction_ingestion"
down_revision = "20261009_settlement_scheduler"
branch_labels = None
depends_on = None


_TRANSACTION_SOURCE_FIELDS = (
    "raw_payload",
    "trans_id",
    "tenant_id",
    "provider_account_id",
    "provider",
    "idempotency_key",
    "external_reference",
    "provider_transaction_id",
    "trans_amount",
    "currency",
    "msisdn",
    "business_short_code",
    "trans_time",
    "raw_payload_hash",
    "source_schema_version",
)


def upgrade() -> None:
    op.add_column(
        "transactions",
        sa.Column("raw_payload_hash", sa.String(length=64), nullable=True),
    )
    op.add_column(
        "transactions",
        sa.Column(
            "source_schema_version",
            sa.String(length=32),
            nullable=False,
            server_default="1.0",
        ),
    )
    op.add_column(
        "import_jobs",
        sa.Column("attempts", sa.Integer(), nullable=False, server_default=sa.text("0")),
    )
    op.add_column(
        "import_jobs",
        sa.Column("lease_expires_at", sa.DateTime(timezone=True), nullable=True),
    )
    with op.batch_alter_table("import_jobs") as batch:
        batch.create_check_constraint("ck_import_jobs_attempts_nonnegative", "attempts >= 0")

    dialect = op.get_bind().dialect.name
    trigger_fields = ", ".join(_TRANSACTION_SOURCE_FIELDS)
    immutable_fields = tuple(
        field for field in _TRANSACTION_SOURCE_FIELDS if field not in {"raw_payload", "msisdn"}
    )
    if dialect == "sqlite":
        op.execute(
            "CREATE TRIGGER trg_transactions_immutable_source "
            "BEFORE UPDATE OF " + trigger_fields + " ON transactions "
            "WHEN (" + " OR ".join(
                f"NEW.{field} IS NOT OLD.{field}" for field in immutable_fields
            ) + ") "
            "OR (NEW.raw_payload IS NOT OLD.raw_payload "
            "AND json_extract(NEW.raw_payload, '$.redacted') IS NOT 1) "
            "OR (NEW.msisdn IS NOT OLD.msisdn "
            "AND json_extract(NEW.raw_payload, '$.redacted') IS NOT 1) "
            "BEGIN SELECT RAISE(ABORT, 'transaction source data is immutable'); END"
        )
    elif dialect == "postgresql":
        op.execute(
            """
            CREATE FUNCTION pesaguard_reject_transaction_source_mutation()
            RETURNS trigger AS $$
            BEGIN
                IF ROW(
                    NEW.trans_id, NEW.tenant_id, NEW.provider_account_id,
                    NEW.provider, NEW.idempotency_key,
                    NEW.external_reference, NEW.provider_transaction_id,
                    NEW.trans_amount, NEW.currency, NEW.business_short_code,
                    NEW.trans_time, NEW.raw_payload_hash, NEW.source_schema_version
                ) IS DISTINCT FROM ROW(
                    OLD.trans_id, OLD.tenant_id, OLD.provider_account_id,
                    OLD.provider, OLD.idempotency_key,
                    OLD.external_reference, OLD.provider_transaction_id,
                    OLD.trans_amount, OLD.currency, OLD.business_short_code,
                    OLD.trans_time, OLD.raw_payload_hash, OLD.source_schema_version
                ) THEN
                    RAISE EXCEPTION 'transaction source data is immutable';
                END IF;
                IF NEW.raw_payload IS DISTINCT FROM OLD.raw_payload
                    AND COALESCE(NEW.raw_payload->>'redacted', 'false') <> 'true' THEN
                    RAISE EXCEPTION 'transaction raw payload is immutable';
                END IF;
                IF NEW.msisdn IS DISTINCT FROM OLD.msisdn
                    AND COALESCE(NEW.raw_payload->>'redacted', 'false') <> 'true' THEN
                    RAISE EXCEPTION 'transaction source data is immutable';
                END IF;
                RETURN NEW;
            END;
            $$ LANGUAGE plpgsql
            """
        )
        op.execute(
            "CREATE TRIGGER trg_transactions_immutable_source "
            "BEFORE UPDATE OF " + trigger_fields + " ON transactions "
            "FOR EACH ROW EXECUTE FUNCTION pesaguard_reject_transaction_source_mutation()"
        )
    else:
        raise RuntimeError(f"transaction immutability is unsupported for database dialect {dialect}")


def downgrade() -> None:
    dialect = op.get_bind().dialect.name
    if dialect == "postgresql":
        op.execute("DROP TRIGGER IF EXISTS trg_transactions_immutable_source ON transactions")
        op.execute("DROP FUNCTION IF EXISTS pesaguard_reject_transaction_source_mutation()")
    elif dialect == "sqlite":
        op.execute("DROP TRIGGER IF EXISTS trg_transactions_immutable_source")
    with op.batch_alter_table("import_jobs") as batch:
        batch.drop_constraint("ck_import_jobs_attempts_nonnegative", type_="check")
    op.drop_column("import_jobs", "lease_expires_at")
    op.drop_column("import_jobs", "attempts")
    op.drop_column("transactions", "source_schema_version")
    op.drop_column("transactions", "raw_payload_hash")
