"""Allow-listed scheduled task handlers for the durable scheduler."""

from __future__ import annotations

import logging
from datetime import datetime, timedelta, timezone
from typing import Any, Dict

from scheduler import ScheduledTaskContext, SchedulerError

logger = logging.getLogger("pesaguard.scheduled_tasks")


def reconcile_pending_transactions(
    payload: Dict[str, Any],
    context: ScheduledTaskContext,
) -> Dict[str, int]:
    """Reconcile one bounded tenant batch of pending durable transactions."""
    from api_core_jobs import process_reconciliation_request
    from models import ProcessedTransaction, Transaction
    from reconciliation_job import AuditSession

    batch_size = payload.get("batch_size", 100)
    if type(batch_size) is not int or not 1 <= batch_size <= 500:
        raise SchedulerError("batch_size must be between 1 and 500")

    session = AuditSession()
    try:
        statement = (
            session.query(Transaction.id)
            .join(
                ProcessedTransaction,
                (
                    (ProcessedTransaction.tenant_id == Transaction.tenant_id)
                    & (ProcessedTransaction.daraja_trans_id == Transaction.trans_id)
                    & (ProcessedTransaction.provider_account_id == Transaction.provider_account_id)
                ),
            )
            .filter(
                Transaction.tenant_id == context.tenant_id,
                ProcessedTransaction.tenant_id == context.tenant_id,
                ProcessedTransaction.reconciliation_status.in_(("pending", "failed")),
            )
        )
        if context.trigger_type == "backfill":
            if context.window_start is None or context.window_end is None:
                raise SchedulerError("backfill execution requires a complete time window")
            statement = statement.filter(
                ProcessedTransaction.received_at >= context.window_start,
                ProcessedTransaction.received_at < context.window_end,
            )
        transaction_ids = [
            row[0]
            for row in statement.order_by(
                ProcessedTransaction.received_at.asc(),
                Transaction.id.asc(),
            ).limit(batch_size).all()
        ]
    finally:
        session.close()

    completed = 0
    for transaction_id in transaction_ids:
        result = process_reconciliation_request(context.tenant_id, transaction_id)
        if result.get("status") != "completed":
            raise SchedulerError(
                "reconciliation batch contains a transaction that did not complete"
            )
        completed += 1
    return {"selected": len(transaction_ids), "completed": completed}


def archive_tenant_transactions(
    payload: Dict[str, Any],
    context: ScheduledTaskContext,
) -> Dict[str, int]:
    """Archive one tenant's eligible transactions and verify before DB marking."""
    from archival import ArchiveManager
    from database_service import create_database_engine
    from environment import required_env
    from lifecycle import transition_data_lifecycle
    from models import Transaction
    from sqlalchemy.orm import sessionmaker

    batch_size = payload.get("batch_size", 100)
    if type(batch_size) is not int or not 1 <= batch_size <= 500:
        raise SchedulerError("batch_size must be between 1 and 500")
    if not context.tenant_id:
        raise SchedulerError("transaction archival requires a tenant context")

    manager = ArchiveManager.from_environment()
    engine = create_database_engine(required_env("DATABASE_URL"))
    session_factory = sessionmaker(bind=engine, expire_on_commit=False)
    now = datetime.now(timezone.utc)
    cold_cutoff = now - timedelta(days=manager.policy.cold_days)
    hot_cutoff = now - timedelta(days=manager.policy.hot_days)
    try:
        with session_factory() as session:
            stale_unarchived = (
                session.query(Transaction.id)
                .filter(
                    Transaction.tenant_id == context.tenant_id,
                    Transaction.archive_object_key.is_(None),
                    Transaction.archived_at.is_(None),
                    Transaction.created_at <= cold_cutoff,
                    Transaction.lifecycle_stage.in_(("STORED", "CONSUMED", "ARCHIVED")),
                )
                .count()
            )
            transactions = (
                session.query(Transaction.id)
                .filter(
                    Transaction.tenant_id == context.tenant_id,
                    Transaction.archive_object_key.is_(None),
                    Transaction.archived_at.is_(None),
                    Transaction.created_at > cold_cutoff,
                    Transaction.created_at <= hot_cutoff,
                    Transaction.lifecycle_stage.in_(("STORED", "CONSUMED")),
                )
                .order_by(Transaction.created_at.asc(), Transaction.id.asc())
                .limit(batch_size)
                .all()
            )
            transaction_ids = [transaction_id for (transaction_id,) in transactions]

        archived = 0
        for transaction_id in transaction_ids:
            with session_factory() as session:
                transaction = (
                    session.query(Transaction)
                    .filter(
                        Transaction.id == transaction_id,
                        Transaction.tenant_id == context.tenant_id,
                        Transaction.archive_object_key.is_(None),
                    )
                    .one_or_none()
                )
                if transaction is None:
                    continue
                archive_payload = {
                    "tenant_id": transaction.tenant_id,
                    "transaction_id": transaction.id,
                    "provider_transaction_id": transaction.provider_transaction_id,
                    "provider_account_id": transaction.provider_account_id,
                    "provider": transaction.provider,
                    "transaction_time": transaction.created_at.isoformat(),
                    "currency": transaction.currency,
                    "amount": str(transaction.trans_amount),
                    "source_schema_version": transaction.source_schema_version,
                    "raw_payload_hash": transaction.raw_payload_hash,
                    "raw_payload": transaction.raw_payload,
                }

            manifest = manager.archive(archive_payload, now=now)
            if manifest is None:
                continue
            manager.read_verified(manifest)

            with session_factory() as session:
                locked_transaction = (
                    session.query(Transaction)
                    .filter(
                        Transaction.id == transaction_id,
                        Transaction.tenant_id == context.tenant_id,
                        Transaction.archive_object_key.is_(None),
                    )
                    .with_for_update()
                    .one_or_none()
                )
                if locked_transaction is None:
                    session.rollback()
                    continue
                if locked_transaction.lifecycle_stage != "ARCHIVED":
                    locked_transaction.lifecycle_stage = transition_data_lifecycle(
                        locked_transaction.lifecycle_stage,
                        "ARCHIVED",
                    )
                locked_transaction.archive_object_key = manifest.object_key
                locked_transaction.archived_at = now
                session.commit()
                archived += 1

        if stale_unarchived:
            logger.critical(
                "transaction_archive_window_missed tenant_id=%s count=%d",
                context.tenant_id,
                stale_unarchived,
            )
            raise SchedulerError(
                "transactions beyond the archive window remain unarchived; "
                "retention purge must remain blocked"
            )

        return {"selected": len(transaction_ids), "archived": archived}
    finally:
        engine.dispose()


TASK_HANDLERS = {
    "reconciliation.pending_transactions": reconcile_pending_transactions,
    "data.archive_transactions": archive_tenant_transactions,
}
