"""
Data Retention Cleanup Job for PesaGuard Operational Data.

Purges expired raw transactions, reconciled discrepancies, dead letters, and audit logs
based on tenant-configurable retention policies to comply with data privacy regulations
and optimize PostgreSQL storage footprint.
"""

from __future__ import annotations

from environment import required_env

import logging
import os
import sys
from dataclasses import asdict, dataclass
from datetime import datetime, timedelta, timezone
from typing import Any, Callable, Dict, Optional
from uuid import uuid4

from sqlalchemy import and_, delete, func, or_, select
from sqlalchemy.orm import sessionmaker

# Resilient import handling across package layouts
try:
    from models import Base, DeadLetter, Discrepancy, ProcessedTransaction, ReconciliationOutbox, Transaction, TransactionOutbox
    from action_audit import ActionAuditEntry, AuditLegalHold, MAX_RESOURCE_ID_LENGTH
except ImportError:
    try:
        from pesaguard_backend_pipeline.models import Base, DeadLetter, Discrepancy, ProcessedTransaction, ReconciliationOutbox, Transaction, TransactionOutbox
        from pesaguard_backend_pipeline.action_audit import ActionAuditEntry, AuditLegalHold, MAX_RESOURCE_ID_LENGTH
    except ImportError:
        sys.path.append(os.path.join(os.path.dirname(__file__), ".."))
        from models import Base, DeadLetter, Discrepancy, ProcessedTransaction, ReconciliationOutbox, Transaction, TransactionOutbox
        from action_audit import ActionAuditEntry, AuditLegalHold, MAX_RESOURCE_ID_LENGTH

try:
    from operations import retention_approval
except ImportError:
    from pesaguard_backend_pipeline.operations import retention_approval

logging.basicConfig(
    level=os.getenv("LOG_LEVEL", "INFO").upper(),
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)
logger = logging.getLogger("pesaguard.retention_cleanup")

from retention_policy import load_retention_policy

_RETENTION_POLICY = load_retention_policy()

def get_database_url() -> str:
    return required_env("DATABASE_URL")

# Default Retention Windows (in days)

def _positive_setting(name: str, default: int) -> int:
    raw = os.getenv(name, str(default))
    try:
        value = int(raw)
    except ValueError as exc:
        raise ValueError(f"{name} must be an integer") from exc
    if value < 1:
        raise ValueError(f"{name} must be greater than zero")
    return value


RETENTION_DAYS_TRANSACTIONS = _positive_setting(
    "PESAGUARD_RETENTION_DAYS_TRANSACTIONS",
    _RETENTION_POLICY.retention_days("transactions"),
)


def _optional_retention_setting(category: str) -> Optional[int]:
    setting_name = f"PESAGUARD_RETENTION_DAYS_{category.upper()}"
    raw_value = os.getenv(setting_name)
    if raw_value is not None:
        return _positive_setting(setting_name, 0)
    if category in _RETENTION_POLICY.rules:
        return _RETENTION_POLICY.retention_days(category)
    return None


RETENTION_DAYS_DISCREPANCIES = _optional_retention_setting("discrepancies")
RETENTION_DAYS_DEAD_LETTERS = _positive_setting(
    "PESAGUARD_RETENTION_DAYS_DEAD_LETTERS",
    _RETENTION_POLICY.retention_days("security_logs"),
)
RETENTION_DAYS_AUDIT = _positive_setting(
    "PESAGUARD_RETENTION_DAYS_AUDIT",
    _RETENTION_POLICY.retention_days("audit"),
)

# Deletion batch size to prevent long-lived table locks and WAL bloat
BATCH_SIZE = _positive_setting("PESAGUARD_RETENTION_BATCH_SIZE", 1000)


@dataclass(frozen=True)
class _RetentionApprovalContext:
    reference: str
    run_id: str
    policy_sha256: str
    verified: retention_approval.VerifiedRetentionApproval


def _effective_retention_policy_sha256() -> str:
    return retention_approval.policy_sha256({
        "version": _RETENTION_POLICY.version,
        "approval_required": _RETENTION_POLICY.approval_required,
        "rules": {
            category: asdict(rule)
            for category, rule in sorted(_RETENTION_POLICY.rules.items())
        },
        "cleanup_windows_days": {
            "transactions": RETENTION_DAYS_TRANSACTIONS,
            "discrepancies": RETENTION_DAYS_DISCREPANCIES,
            "dead_letters": RETENTION_DAYS_DEAD_LETTERS,
            "audit": RETENTION_DAYS_AUDIT,
        },
        "batch_size": BATCH_SIZE,
    })


def _validate_retention_approval_reference(reference: Optional[str]) -> str:
    if not _RETENTION_POLICY.approval_required:
        raise ValueError("destructive retention cleanup requires approval_required=true")
    if not isinstance(reference, str) or not reference.strip():
        raise ValueError("approval_reference is required for destructive retention cleanup")
    normalized_reference = reference.strip()
    if len(normalized_reference) > MAX_RESOURCE_ID_LENGTH:
        raise ValueError(f"approval_reference must be at most {MAX_RESOURCE_ID_LENGTH} characters")
    return normalized_reference


def get_engine():
    """Construct database engine instance with connection pooling."""
    from database_service import create_database_engine

    database_url = get_database_url()
    return create_database_engine(
        database_url,
        connect_args={"connect_timeout": 10} if database_url.startswith("postgresql") else {},
    )


def get_session_factory():
    """Return ORM session factory."""
    return sessionmaker(bind=get_engine(), expire_on_commit=False)


def _hold_exclusion(model, now: datetime):
    resource_columns = [column for column in (
        getattr(model, "id", None), getattr(model, "trans_id", None),
        getattr(model, "daraja_trans_id", None), getattr(model, "event_key", None),
    ) if column is not None]
    resource_match = and_(
        AuditLegalHold.scope_type.in_(["resource", "transaction"]),
        or_(*[AuditLegalHold.scope_id == column for column in resource_columns]),
    ) if resource_columns else False
    return ~select(AuditLegalHold.id).where(
        AuditLegalHold.tenant_id == model.tenant_id,
        AuditLegalHold.active.is_(True),
        or_(AuditLegalHold.expires_at.is_(None), AuditLegalHold.expires_at > now),
        or_(AuditLegalHold.scope_type == "tenant", resource_match),
    ).correlate(model).exists()


def _delete_in_batches(
    session,
    model,
    time_column,
    cutoff_dt: datetime,
    tenant_id: Optional[str] = None,
    dry_run: bool = False,
    extra_filter=None,
    before_delete: Optional[Callable[[Any], bool]] = None,
) -> int:
    """Safely purge records older than cutoff timestamp in small transaction chunks."""
    total_deleted = 0

    while True:
        # Construct primary key subquery for batch deletion
        eligibility = [time_column < cutoff_dt, _hold_exclusion(model, datetime.now(timezone.utc))]
        if extra_filter is not None:
            eligibility.append(extra_filter)
        subquery = select(model.id if hasattr(model, "id") else model.trans_id).where(*eligibility)
        if tenant_id and hasattr(model, "tenant_id"):
            subquery = subquery.where(model.tenant_id == tenant_id)

        subquery = subquery.limit(BATCH_SIZE)

        if dry_run:
            count_query = select(func.count()).select_from(model).where(*eligibility)
            if tenant_id and hasattr(model, "tenant_id"):
                count_query = count_query.where(model.tenant_id == tenant_id)
            count = session.scalar(count_query) or 0
            if before_delete is not None:
                candidates = session.query(model).filter(
                    model.id.in_(subquery)
                ).all()
                for candidate in candidates:
                    if before_delete(candidate) is not True:
                        raise RuntimeError("archive verifier did not confirm transaction integrity")
            return count

        if before_delete is not None:
            candidates = session.query(model).filter(
                model.id.in_(subquery)
            ).all()
            for candidate in candidates:
                if before_delete(candidate) is not True:
                    raise RuntimeError("archive verifier did not confirm transaction integrity")

        # Execute chunked deletion
        if hasattr(model, "id"):
            stmt = delete(model).where(model.id.in_(subquery))
        else:
            stmt = delete(model).where(model.trans_id.in_(subquery))

        result = session.execute(stmt)
        rows_affected = result.rowcount
        session.commit()

        total_deleted += rows_affected
        if rows_affected < BATCH_SIZE:
            break

    return total_deleted


def _count_audit_archival_candidates(session, cutoff_dt: datetime, tenant_id: Optional[str] = None) -> int:
    """Count expired audit rows eligible for archival without mutating append-only logs."""
    base_filter = [
        ActionAuditEntry.created_at < cutoff_dt,
        ~select(AuditLegalHold.id).where(
            AuditLegalHold.tenant_id == ActionAuditEntry.tenant_id,
            AuditLegalHold.active.is_(True),
            or_(AuditLegalHold.expires_at.is_(None), AuditLegalHold.expires_at > datetime.now(timezone.utc)),
            or_(
                AuditLegalHold.scope_type == "tenant",
                and_(AuditLegalHold.scope_type == ActionAuditEntry.resource_type, AuditLegalHold.scope_id == ActionAuditEntry.resource_id),
            ),
        ).correlate(ActionAuditEntry).exists(),
    ]
    if tenant_id:
        base_filter.append(ActionAuditEntry.tenant_id == tenant_id)

    return session.scalar(select(func.count()).select_from(ActionAuditEntry).where(*base_filter)) or 0


def _record_retention_audit(
    session,
    tenant_id: Optional[str],
    phase: str,
    outcome: str,
    approved_by: str,
    approval_reference: str,
    details: Optional[Dict[str, Any]] = None,
) -> None:
    """Persist approval and result evidence before reporting a cleanup outcome."""
    audit_tenant_id = tenant_id or "__system__"
    audit_details = {
        "phase": phase,
        "approved_by": approved_by,
        "approval_reference": approval_reference,
        "scope_tenant_id": tenant_id or "all",
    }
    if details:
        audit_details.update(details)
    session.add(ActionAuditEntry(
        tenant_id=audit_tenant_id,
        actor="retention-cleanup",
        action="retention.cleanup",
        category="operations",
        outcome=outcome,
        actor_type="system",
        details=audit_details,
    ))
    session.commit()


def cleanup_retention(
    tenant_id: Optional[str] = None,
    dry_run: bool = False,
    approval_reference: Optional[str] = None,
    archive_verifier: Optional[Callable[[Transaction], bool]] = None,
) -> Dict[str, Any]:
    """Execute retention policy cleanup across operational tables.

    Args:
        tenant_id: Optional tenant filter to target specific tenant data.
        dry_run: If True, returns estimated counts without deleting rows.
        approval_reference: Identifier for an approved, one-time external
            retention authorization. The approver identity is read from the
            verified external approval response.

    Returns:
        Telemetry summary dict with deleted record counts.
    """
    if tenant_id is not None:
        if not tenant_id.strip():
            raise ValueError("tenant_id must not be empty")
        tenant_id = tenant_id.strip()

    approval_context = None
    policy_hash = _effective_retention_policy_sha256()
    if not dry_run:
        normalized_reference = _validate_retention_approval_reference(approval_reference)
        run_id = uuid4().hex
        verified_approval = retention_approval.verify_retention_approval(
            normalized_reference,
            tenant_id=tenant_id,
            expected_policy_sha256=policy_hash,
        )
        approval_context = _RetentionApprovalContext(
            reference=normalized_reference,
            run_id=run_id,
            policy_sha256=policy_hash,
            verified=verified_approval,
        )

    now = datetime.now(timezone.utc)
    oldest_transaction = now - timedelta(days=RETENTION_DAYS_TRANSACTIONS)
    oldest_discrepancy = (
        now - timedelta(days=RETENTION_DAYS_DISCREPANCIES)
        if RETENTION_DAYS_DISCREPANCIES is not None
        else None
    )
    oldest_dead_letter = now - timedelta(days=RETENTION_DAYS_DEAD_LETTERS)
    oldest_audit = now - timedelta(days=RETENTION_DAYS_AUDIT)

    logger.info(
        "Starting data retention cleanup (dry_run=%s, tenant_id=%s)...",
        dry_run, tenant_id or "all"
    )

    cleanup_engine = get_engine()
    SessionLocal = sessionmaker(bind=cleanup_engine, expire_on_commit=False)
    session = SessionLocal()

    try:
        if approval_context is not None:
            approval_details = {
                "approval_source": "external_api",
                "run_id": approval_context.run_id,
                "policy_sha256": approval_context.policy_sha256,
                "approval_expires_at": approval_context.verified.expires_at.isoformat(),
            }
            _record_retention_audit(
                session,
                tenant_id,
                "approval_verified",
                "success",
                approval_context.verified.approver_id,
                approval_context.reference,
                {
                    **approval_details,
                    "retention_windows": {
                        "transactions_days": RETENTION_DAYS_TRANSACTIONS,
                        "discrepancies_days": RETENTION_DAYS_DISCREPANCIES,
                        "dead_letters_days": RETENTION_DAYS_DEAD_LETTERS,
                        "audit_days": RETENTION_DAYS_AUDIT,
                    },
                },
            )
            retention_approval.consume_retention_approval(
                approval_context.reference,
                approval_context.verified,
                approval_context.run_id,
            )
            _record_retention_audit(
                session,
                tenant_id,
                "started",
                "success",
                approval_context.verified.approver_id,
                approval_context.reference,
                {
                    **approval_details,
                },
            )

        archived_transaction_filter = and_(
            Transaction.archived_at.is_not(None),
            Transaction.archive_object_key.is_not(None),
            Transaction.archive_object_key != "",
        )
        transaction_purge_candidates = _delete_in_batches(
            session,
            Transaction,
            Transaction.created_at,
            oldest_transaction,
            tenant_id,
            dry_run=True,
            extra_filter=archived_transaction_filter,
        )
        deleted_transactions = (
            _delete_in_batches(
                session,
                Transaction,
                Transaction.created_at,
                oldest_transaction,
                tenant_id,
                dry_run,
                archived_transaction_filter,
                before_delete=archive_verifier,
            )
            if archive_verifier is not None
            else 0
        )
        deleted_processed = _delete_in_batches(
            session, ProcessedTransaction, ProcessedTransaction.received_at, oldest_transaction, tenant_id, dry_run
        )
        deleted_discrepancies = (
            _delete_in_batches(
                session, Discrepancy, Discrepancy.detected_at, oldest_discrepancy, tenant_id, dry_run
            )
            if oldest_discrepancy is not None
            else 0
        )
        deleted_dead_letters = _delete_in_batches(
            session, DeadLetter, DeadLetter.created_at, oldest_dead_letter, tenant_id, dry_run
        )
        deleted_transaction_outbox = _delete_in_batches(
            session, TransactionOutbox, TransactionOutbox.created_at, oldest_transaction, tenant_id, dry_run,
            TransactionOutbox.status == "published",
        )
        deleted_reconciliation_outbox = (
            _delete_in_batches(
                session,
                ReconciliationOutbox,
                ReconciliationOutbox.created_at,
                oldest_discrepancy,
                tenant_id,
                dry_run,
                ReconciliationOutbox.status == "published",
            )
            if oldest_discrepancy is not None
            else 0
        )
        eligible_audit_entries = _count_audit_archival_candidates(session, oldest_audit, tenant_id)
        # Audit rows are append-only by database policy. A separate archival
        # exporter must move eligible rows before any future purge capability.
        deleted_audit = 0

        def post_delete_count(model, time_column, cutoff_dt, extra_filter=None):
            if cutoff_dt is None:
                return None
            filters = [time_column < cutoff_dt]
            if tenant_id and hasattr(model, "tenant_id"):
                filters.append(model.tenant_id == tenant_id)
            if extra_filter is not None:
                filters.append(extra_filter)
            return session.scalar(select(func.count()).select_from(model).where(*filters)) or 0

        metrics = {
            "status": "success",
            "dry_run": dry_run,
            "tenant_id": tenant_id or "all",
            "retention_policy_sha256": policy_hash,
            "deleted_transactions": deleted_transactions,
            "transaction_purge_candidates": transaction_purge_candidates,
            "deleted_processed_transactions": deleted_processed,
            "deleted_discrepancies": deleted_discrepancies,
            "deleted_dead_letters": deleted_dead_letters,
            "deleted_transaction_outbox": deleted_transaction_outbox,
            "deleted_reconciliation_outbox": deleted_reconciliation_outbox,
            "deleted_audit_entries": deleted_audit,
            "deleted_audit": deleted_audit,
            "eligible_audit_entries": eligible_audit_entries,
            "skipped_categories": (
                (["discrepancies", "reconciliation_outbox"] if oldest_discrepancy is None else [])
                + (["transactions"] if archive_verifier is None else [])
            ),
            "post_delete_counts": {
                "transactions": post_delete_count(
                    Transaction,
                    Transaction.created_at,
                    oldest_transaction,
                    and_(
                        Transaction.archived_at.is_not(None),
                        Transaction.archive_object_key.is_not(None),
                        Transaction.archive_object_key != "",
                    ),
                ),
                "processed_transactions": post_delete_count(ProcessedTransaction, ProcessedTransaction.received_at, oldest_transaction),
                "discrepancies": post_delete_count(Discrepancy, Discrepancy.detected_at, oldest_discrepancy),
                "dead_letters": post_delete_count(DeadLetter, DeadLetter.created_at, oldest_dead_letter),
                "transaction_outbox": post_delete_count(TransactionOutbox, TransactionOutbox.created_at, oldest_transaction, TransactionOutbox.status == "published"),
                "reconciliation_outbox": post_delete_count(ReconciliationOutbox, ReconciliationOutbox.created_at, oldest_discrepancy, ReconciliationOutbox.status == "published"),
            },
            "retention_windows": {
                "transactions_days": RETENTION_DAYS_TRANSACTIONS,
                "discrepancies_days": RETENTION_DAYS_DISCREPANCIES,
                "dead_letters_days": RETENTION_DAYS_DEAD_LETTERS,
                "audit_days": RETENTION_DAYS_AUDIT,
            },
        }

        if approval_context is not None:
            _record_retention_audit(
                session,
                tenant_id,
                "completed",
                "success",
                approval_context.verified.approver_id,
                approval_context.reference,
                {
                    "approval_source": "external_api",
                    "run_id": approval_context.run_id,
                    "policy_sha256": approval_context.policy_sha256,
                    "deleted_transactions": deleted_transactions,
                    "transaction_purge_candidates": transaction_purge_candidates,
                    "deleted_processed_transactions": deleted_processed,
                    "deleted_discrepancies": deleted_discrepancies,
                    "deleted_dead_letters": deleted_dead_letters,
                    "deleted_transaction_outbox": deleted_transaction_outbox,
                    "deleted_reconciliation_outbox": deleted_reconciliation_outbox,
                    "eligible_audit_entries": eligible_audit_entries,
                },
            )

        logger.info("Retention cleanup finished cleanly: %s", metrics)
        return metrics

    except Exception as exc:
        session.rollback()
        logger.exception("Data retention cleanup job failed: %s", exc)
        if approval_context is not None:
            try:
                _record_retention_audit(
                    session,
                    tenant_id,
                    "failed",
                    "failure",
                    approval_context.verified.approver_id,
                    approval_context.reference,
                    {
                        "approval_source": "external_api",
                        "run_id": approval_context.run_id,
                        "policy_sha256": approval_context.policy_sha256,
                        "error_type": type(exc).__name__,
                    },
                )
            except Exception:
                logger.exception("Failed to persist retention cleanup failure audit")
        raise
    finally:
        session.close()
        cleanup_engine.dispose()


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser(description="PesaGuard Data Retention Cleanup Utility")
    parser.add_argument("--dry-run", action="store_true", help="Preview row counts without performing deletions")
    parser.add_argument("--tenant-id", type=str, help="Scope deletion to a specific tenant ID")
    parser.add_argument("--approval-reference", type=str, help="Approved one-time external retention authorization")
    args = parser.parse_args()
    if not args.dry_run and (not args.approval_reference or not args.approval_reference.strip()):
        parser.error("--approval-reference is required for destructive cleanup")

    result_summary = cleanup_retention(
        tenant_id=args.tenant_id,
        dry_run=args.dry_run,
        approval_reference=args.approval_reference,
    )
    print("Retention Cleanup Summary:")
    for key, val in result_summary.items():
        print(f"  {key}: {val}")
