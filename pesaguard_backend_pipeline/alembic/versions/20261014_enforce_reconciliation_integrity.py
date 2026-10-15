"""Enforce financial lifecycle edges and immutable reconciliation decisions."""

from alembic import op


revision = "20261014_enforce_reconciliation_integrity"
down_revision = "20261013_customer_account_ledger"
branch_labels = None
depends_on = None


_TRANSACTION_EDGES = """
    (OLD.status = 'RECEIVED' AND NEW.status IN ('VALIDATED', 'FAILED', 'REJECTED'))
    OR (OLD.status = 'VALIDATED' AND NEW.status IN ('PROCESSING', 'FAILED'))
    OR (OLD.status = 'PROCESSING' AND NEW.status IN ('RECONCILING', 'FAILED'))
    OR (OLD.status = 'RECONCILING' AND NEW.status IN ('RECONCILED', 'FAILED'))
    OR (OLD.status = 'FAILED' AND NEW.status = 'PROCESSING')
"""
_RECONCILIATION_EDGES = """
    (OLD.reconciliation_status = 'pending'
        AND NEW.reconciliation_status IN ('processing', 'failed'))
    OR (OLD.reconciliation_status = 'processing'
        AND NEW.reconciliation_status IN ('completed', 'failed'))
    OR (OLD.reconciliation_status = 'failed'
        AND NEW.reconciliation_status = 'processing')
"""


def upgrade() -> None:
    dialect = op.get_bind().dialect.name
    if dialect == "sqlite":
        op.execute(
            "CREATE TRIGGER trg_transactions_lifecycle "
            "BEFORE UPDATE OF status ON transactions "
            "WHEN NEW.status IS NOT OLD.status AND NOT ("
            + _TRANSACTION_EDGES
            + ") BEGIN SELECT RAISE(ABORT, 'invalid transaction lifecycle transition'); END"
        )
        op.execute(
            "CREATE TRIGGER trg_processed_reconciliation_lifecycle "
            "BEFORE UPDATE OF reconciliation_status ON processed_transactions "
            "WHEN NEW.reconciliation_status IS NOT OLD.reconciliation_status AND NOT ("
            + _RECONCILIATION_EDGES
            + ") BEGIN SELECT RAISE(ABORT, 'invalid reconciliation lifecycle transition'); END"
        )
        op.execute(
            "CREATE TRIGGER trg_reconciliation_matches_immutable_update "
            "BEFORE UPDATE ON reconciliation_matches "
            "BEGIN SELECT RAISE(ABORT, 'reconciliation decisions are append-only'); END"
        )
        op.execute(
            "CREATE TRIGGER trg_reconciliation_matches_immutable_delete "
            "BEFORE DELETE ON reconciliation_matches "
            "BEGIN SELECT RAISE(ABORT, 'reconciliation decisions are append-only'); END"
        )
        return
    if dialect != "postgresql":
        raise RuntimeError(f"reconciliation integrity is unsupported for database dialect {dialect}")

    op.execute(
        """
        CREATE FUNCTION pesaguard_enforce_transaction_lifecycle()
        RETURNS trigger AS $$
        BEGIN
            IF NEW.status IS DISTINCT FROM OLD.status AND NOT (
                (OLD.status = 'RECEIVED' AND NEW.status IN ('VALIDATED', 'FAILED', 'REJECTED'))
                OR (OLD.status = 'VALIDATED' AND NEW.status IN ('PROCESSING', 'FAILED'))
                OR (OLD.status = 'PROCESSING' AND NEW.status IN ('RECONCILING', 'FAILED'))
                OR (OLD.status = 'RECONCILING' AND NEW.status IN ('RECONCILED', 'FAILED'))
                OR (OLD.status = 'FAILED' AND NEW.status = 'PROCESSING')
            ) THEN
                RAISE EXCEPTION 'invalid transaction lifecycle transition';
            END IF;
            RETURN NEW;
        END;
        $$ LANGUAGE plpgsql
        """
    )
    op.execute(
        "CREATE TRIGGER trg_transactions_lifecycle "
        "BEFORE UPDATE OF status ON transactions "
        "FOR EACH ROW EXECUTE FUNCTION pesaguard_enforce_transaction_lifecycle()"
    )
    op.execute(
        """
        CREATE FUNCTION pesaguard_enforce_reconciliation_lifecycle()
        RETURNS trigger AS $$
        BEGIN
            IF NEW.reconciliation_status IS DISTINCT FROM OLD.reconciliation_status AND NOT (
                (OLD.reconciliation_status = 'pending'
                    AND NEW.reconciliation_status IN ('processing', 'failed'))
                OR (OLD.reconciliation_status = 'processing'
                    AND NEW.reconciliation_status IN ('completed', 'failed'))
                OR (OLD.reconciliation_status = 'failed'
                    AND NEW.reconciliation_status = 'processing')
            ) THEN
                RAISE EXCEPTION 'invalid reconciliation lifecycle transition';
            END IF;
            RETURN NEW;
        END;
        $$ LANGUAGE plpgsql
        """
    )
    op.execute(
        "CREATE TRIGGER trg_processed_reconciliation_lifecycle "
        "BEFORE UPDATE OF reconciliation_status ON processed_transactions "
        "FOR EACH ROW EXECUTE FUNCTION pesaguard_enforce_reconciliation_lifecycle()"
    )
    op.execute(
        """
        CREATE FUNCTION pesaguard_reject_reconciliation_match_mutation()
        RETURNS trigger AS $$
        BEGIN
            RAISE EXCEPTION 'reconciliation decisions are append-only';
        END;
        $$ LANGUAGE plpgsql
        """
    )
    op.execute(
        "CREATE TRIGGER trg_reconciliation_matches_immutable "
        "BEFORE UPDATE OR DELETE ON reconciliation_matches "
        "FOR EACH ROW EXECUTE FUNCTION pesaguard_reject_reconciliation_match_mutation()"
    )


def downgrade() -> None:
    dialect = op.get_bind().dialect.name
    if dialect == "sqlite":
        op.execute("DROP TRIGGER IF EXISTS trg_reconciliation_matches_immutable_delete")
        op.execute("DROP TRIGGER IF EXISTS trg_reconciliation_matches_immutable_update")
        op.execute("DROP TRIGGER IF EXISTS trg_processed_reconciliation_lifecycle")
        op.execute("DROP TRIGGER IF EXISTS trg_transactions_lifecycle")
        return
    if dialect != "postgresql":
        raise RuntimeError(f"reconciliation integrity is unsupported for database dialect {dialect}")

    op.execute("DROP TRIGGER IF EXISTS trg_reconciliation_matches_immutable ON reconciliation_matches")
    op.execute("DROP FUNCTION IF EXISTS pesaguard_reject_reconciliation_match_mutation()")
    op.execute("DROP TRIGGER IF EXISTS trg_processed_reconciliation_lifecycle ON processed_transactions")
    op.execute("DROP FUNCTION IF EXISTS pesaguard_enforce_reconciliation_lifecycle()")
    op.execute("DROP TRIGGER IF EXISTS trg_transactions_lifecycle ON transactions")
    op.execute("DROP FUNCTION IF EXISTS pesaguard_enforce_transaction_lifecycle()")
