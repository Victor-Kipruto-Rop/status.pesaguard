"""Raw-to-reconciliation traceability for one tenant-scoped transaction.

Assembles a tamper-evident trail across the durable evidence tables:
transformation stages (raw payload hashes), lineage chain, transaction and
lifecycle events, reconciliation decisions, discrepancies, and dead-letter
mentions. Payload values are never included — only hashes, identifiers, and
statuses — so traces expose provenance without exposing sensitive data.
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from datetime import datetime
from typing import Any, Dict, List, Optional

from sqlalchemy import or_

from models import (
    DeadLetter,
    Discrepancy,
    LineageRecord,
    ProcessedTransaction,
    ReconciliationMatch,
    TransformationRecord,
    Transaction,
    TransactionEvent,
)

DEAD_LETTER_SCAN_LIMIT = 200
DISCREPANCY_LIMIT = 100
EVENT_LIMIT = 100


class TraceError(ValueError):
    """Raised when a trace request is invalid."""


@dataclass(frozen=True)
class StageEvidence:
    stage: str
    event_id: str
    transaction_id: str
    payload_hash: str
    created_at: Optional[datetime]


@dataclass(frozen=True)
class LineageHop:
    event_id: str
    stage: str
    source: str
    transformation_version: str
    job_id: Optional[str]
    correlation_id: Optional[str]
    created_at: Optional[datetime]


@dataclass(frozen=True)
class DiscrepancyEntry:
    id: str
    anomaly_type: str
    status: str
    severity: str


@dataclass(frozen=True)
class DeadLetterEntry:
    id: str
    reason: str
    created_at: Optional[datetime]


@dataclass(frozen=True)
class TransactionTrace:
    tenant_id: str
    transaction_id: str
    stages: List[StageEvidence] = field(default_factory=list)
    lineage: List[LineageHop] = field(default_factory=list)
    transaction_status: Optional[str] = None
    processed_status: Optional[str] = None
    reconciliation_status: Optional[str] = None
    match_score: Optional[float] = None
    discrepancies: List[DiscrepancyEntry] = field(default_factory=list)
    dead_letters: List[DeadLetterEntry] = field(default_factory=list)
    event_count: int = 0

    def stage_names(self) -> List[str]:
        return [stage.stage for stage in self.stages]

    def to_payload(self) -> Dict[str, Any]:
        def serialise(value: Any) -> Any:
            if isinstance(value, datetime):
                return value.isoformat()
            return value

        return {
            "tenant_id": self.tenant_id,
            "transaction_id": self.transaction_id,
            "stages": [
                {
                    "stage": item.stage,
                    "event_id": item.event_id,
                    "transaction_id": item.transaction_id,
                    "payload_hash": item.payload_hash,
                    "created_at": serialise(item.created_at),
                }
                for item in self.stages
            ],
            "lineage": [
                {
                    "event_id": hop.event_id,
                    "stage": hop.stage,
                    "source": hop.source,
                    "transformation_version": hop.transformation_version,
                    "job_id": hop.job_id,
                    "correlation_id": hop.correlation_id,
                    "created_at": serialise(hop.created_at),
                }
                for hop in self.lineage
            ],
            "transaction_status": self.transaction_status,
            "processed_status": self.processed_status,
            "reconciliation_status": self.reconciliation_status,
            "match_score": self.match_score,
            "discrepancies": [
                {
                    "id": item.id,
                    "anomaly_type": item.anomaly_type,
                    "status": item.status,
                    "severity": item.severity,
                }
                for item in self.discrepancies
            ],
            "dead_letters": [
                {
                    "id": item.id,
                    "reason": item.reason,
                    "created_at": serialise(item.created_at),
                }
                for item in self.dead_letters
            ],
            "event_count": self.event_count,
        }


def trace_transaction(session: Any, *, tenant_id: str, transaction_id: str) -> TransactionTrace:
    """Assemble the raw-to-reconciliation trail for one tenant-scoped transaction.

    Every query is filtered by ``tenant_id``; cross-tenant access is
    impossible through this entry point (Rule #11).
    """
    scoped_tenant = str(tenant_id or "").strip()
    scoped_reference = str(transaction_id or "").strip()
    if not scoped_tenant:
        raise TraceError("tenant_id is required")
    if not scoped_reference:
        raise TraceError("transaction_id is required")

    stage_rows = (
        session.query(TransformationRecord)
        .filter(
            TransformationRecord.tenant_id == scoped_tenant,
            or_(
                TransformationRecord.transaction_id == scoped_reference,
                TransformationRecord.event_id == scoped_reference,
            ),
        )
        .order_by(TransformationRecord.created_at.asc())
        .limit(EVENT_LIMIT)
        .all()
    )
    lineage_rows = (
        session.query(LineageRecord)
        .filter(
            LineageRecord.tenant_id == scoped_tenant,
            or_(
                LineageRecord.transaction_id == scoped_reference,
                LineageRecord.event_id == scoped_reference,
            ),
        )
        .order_by(LineageRecord.created_at.asc(), LineageRecord.id.asc())
        .limit(EVENT_LIMIT)
        .all()
    )
    transaction = (
        session.query(Transaction)
        .filter(
            Transaction.tenant_id == scoped_tenant,
            or_(
                Transaction.id == scoped_reference,
                Transaction.trans_id == scoped_reference,
            ),
        )
        .one_or_none()
    )
    trans_id = transaction.trans_id if transaction is not None else scoped_reference
    transaction_guid = transaction.id if transaction is not None else scoped_reference

    event_count = (
        session.query(TransactionEvent.id)
        .filter(
            TransactionEvent.tenant_id == scoped_tenant,
            or_(
                TransactionEvent.transaction_id == transaction_guid,
                TransactionEvent.trans_id == trans_id,
            ),
        )
        .limit(EVENT_LIMIT)
        .count()
    )
    processed = (
        session.query(ProcessedTransaction)
        .filter(
            ProcessedTransaction.tenant_id == scoped_tenant,
            ProcessedTransaction.daraja_trans_id == trans_id,
        )
        .one_or_none()
    )
    match = (
        session.query(ReconciliationMatch)
        .filter(
            ReconciliationMatch.tenant_id == scoped_tenant,
            ReconciliationMatch.transaction_id == trans_id,
        )
        .one_or_none()
    )
    discrepancy_rows = (
        session.query(Discrepancy)
        .filter(
            Discrepancy.tenant_id == scoped_tenant,
            Discrepancy.trans_id == trans_id,
        )
        .order_by(Discrepancy.detected_at.asc())
        .limit(DISCREPANCY_LIMIT)
        .all()
    )
    dead_letter_rows = (
        session.query(DeadLetter)
        .filter(DeadLetter.tenant_id == scoped_tenant)
        .order_by(DeadLetter.created_at.desc())
        .limit(DEAD_LETTER_SCAN_LIMIT)
        .all()
    )
    reference_text = json.dumps(scoped_reference)
    mentioned = [
        row
        for row in dead_letter_rows
        if reference_text[1:-1] in json.dumps(row.payload or {}, default=str)
    ]
    return TransactionTrace(
        tenant_id=scoped_tenant,
        transaction_id=scoped_reference,
        stages=[
            StageEvidence(
                stage=row.stage,
                event_id=row.event_id,
                transaction_id=row.transaction_id,
                payload_hash=row.payload_hash,
                created_at=row.created_at,
            )
            for row in stage_rows
        ],
        lineage=[
            LineageHop(
                event_id=row.event_id,
                stage=row.stage,
                source=row.source,
                transformation_version=row.transformation_version,
                job_id=row.job_id,
                correlation_id=row.correlation_id,
                created_at=row.created_at,
            )
            for row in lineage_rows
        ],
        transaction_status=transaction.status if transaction is not None else None,
        processed_status=(
            processed.reconciliation_status if processed is not None else None
        ),
        reconciliation_status=match.status if match is not None else None,
        match_score=float(match.match_score) if match is not None else None,
        discrepancies=[
            DiscrepancyEntry(
                id=row.id,
                anomaly_type=row.anomaly_type,
                status=row.status,
                severity=row.severity,
            )
            for row in discrepancy_rows
        ],
        dead_letters=[
            DeadLetterEntry(id=row.id, reason=row.reason, created_at=row.created_at)
            for row in mentioned
        ],
        event_count=event_count,
    )
