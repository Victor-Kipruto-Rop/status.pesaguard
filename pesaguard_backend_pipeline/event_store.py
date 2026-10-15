"""A durable event store for transaction idempotency and replay.

IdempotencyRecord preserves request identity and payload hashes; unique
constraints on transaction records close concurrent duplicate-write races.
"""

from __future__ import annotations

from environment import required_env

import logging
import os
import threading
import uuid
import hashlib
import json
from decimal import Decimal
from datetime import datetime, timedelta, timezone
from enum import Enum
from typing import Any, Callable, Dict, Optional

from sqlalchemy import or_
from sqlalchemy.exc import IntegrityError, SQLAlchemyError
from sqlalchemy.orm import Session, sessionmaker

from lifecycle import transition_data_lifecycle, transition_transaction
from event_bus import build_event
from idempotency import derive_idempotency_key
from normalization import NormalizationError, normalize_amount, normalize_currency
from models import AuditEvent, Base, IdempotencyRecord, ProcessedTransaction, ReconciliationOutbox, Transaction, TransactionEvent, TransactionOutbox
from transformation_store import record_transformation_stage
from lineage import record_lineage
from data_protection import protect_payload, tokenize_identifier

logger = logging.getLogger("pesaguard.event_store")

from tenant_context import tenant_scoped_argument

# Providers recognised by the ingestion layer. A provider that is NOT in this
# set is rejected as an "invalid provider reference" so garbled or forged
# provider values can never bypass idempotency scoping.
VALID_PROVIDERS = frozenset(
    {
        "mpesa",
        "airtel-money",
        "equitel",
        "t-kash",
        "safaricom",
        "bank",
        "pos",
        "csv",
        "external-api",
        "webhook",
    }
)


def _money(value: Any, currency: str = "KES") -> Decimal:
    """Normalize provider amounts without binary floating-point conversion."""
    amount = Decimal(normalize_amount(value, currency=currency))
    if amount <= 0:
        raise ValueError("transaction amount must be greater than zero")
    return amount


def _currency(value: Any) -> str:
    """Validate an explicitly provided currency code.

    Missing keys are resolved to the provider default (KES) by callers; an
    explicit empty or non-ISO string is a hard validation error.
    """
    if value is None or not str(value).strip():
        raise ValueError("transaction currency is required and must be a three-letter ISO code")
    try:
        return normalize_currency(value)
    except NormalizationError as exc:
        raise ValueError(str(exc)) from exc


def _resolve_currency(payload: Dict[str, Any]) -> str:
    """Resolve the currency from a payload, defaulting to KES when absent."""
    if "Currency" in payload:
        return _currency(payload["Currency"])
    if "currency" in payload:
        return _currency(payload["currency"])
    return "KES"


def _provider(value: Any) -> str:
    """Normalise and validate the provider reference on an inbound payload.

    A payload that explicitly declares a provider must use one of the known
    provider identifiers; an explicit unknown/empty/garbled provider is an
    invalid provider reference and is rejected.
    """
    raw = str(value or "").strip()
    if not raw:
        raise ValueError("provider reference is required")
    provider = raw.lower()
    if provider not in VALID_PROVIDERS:
        raise ValueError(f"provider '{raw}' is not a recognised provider reference")
    return provider


# Bookkeeping fields injected during normalization (not provider content).
# They carry fresh timestamps on every request, so they must not participate
# in replay-detection hashes: a byte-identical provider retry normalizes to a
# new envelope whose created_at/updated_at differ, which would otherwise be
# misclassified as a conflicting replay (409) instead of a duplicate (200).
# A genuine conflict (same transaction identity, different financial content)
# still changes a hashed field and remains a CONFLICT.
_VOLATILE_HASH_FIELDS = frozenset({"created_at", "updated_at"})


def _payload_hash(payload: Dict[str, Any]) -> str:
    stable = {key: value for key, value in payload.items() if key not in _VOLATILE_HASH_FIELDS}
    canonical = json.dumps(stable, sort_keys=True, separators=(",", ":"), default=str)
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def _source_payload(payload: Dict[str, Any]) -> Dict[str, Any]:
    raw_payload = payload.get("raw_payload")
    return raw_payload if isinstance(raw_payload, dict) else payload


def _source_schema_version(payload: Dict[str, Any]) -> str:
    return str(_source_payload(payload).get("schema_version", "1.0"))


def _source_payload_hash(payload: Dict[str, Any]) -> str:
    canonical = json.dumps(_source_payload(payload), sort_keys=True, separators=(",", ":"), default=str)
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def provider_account_id(payload: Dict[str, Any]) -> str:
    """Resolve the stable Daraja account identity used for idempotency scope."""
    return str(
        payload.get("provider_account_id")
        or payload.get("BusinessShortCode")
        or payload.get("business_short_code")
        or "legacy-default"
    ).strip()


class ProcessResult(str, Enum):
    """Outcome of attempting to record a webhook callback.

    Conflicts mean a request identity was reused for different financial
    content and must not be acknowledged as a duplicate. Errors indicate a
    genuine persistence failure that callers should retry or investigate.
    """

    STORED = "stored"        # new transaction, successfully recorded
    DUPLICATE = "duplicate"  # identical request already recorded
    CONFLICT = "conflict"    # request identity reused with different content
    ERROR = "error"          # genuine failure; callers should retry
    QUARANTINED = "quarantined"  # durably retained for data-quality remediation


def _existing_request_result(
    session: Session,
    *,
    tenant_id: str,
    provider: str,
    idempotency_key: str,
    trans_id: str,
    request_hash: str,
) -> Optional[ProcessResult]:
    existing = session.query(IdempotencyRecord).filter(
        IdempotencyRecord.tenant_id == tenant_id,
        IdempotencyRecord.provider == provider,
        IdempotencyRecord.idempotency_key == idempotency_key,
    ).one_or_none()
    if existing is None:
        existing = session.query(IdempotencyRecord).filter(
            IdempotencyRecord.tenant_id == tenant_id,
            IdempotencyRecord.provider == provider,
            IdempotencyRecord.provider_transaction_id == trans_id,
        ).one_or_none()
    if existing is None:
        return None
    return ProcessResult.DUPLICATE if existing.request_hash == request_hash else ProcessResult.CONFLICT


class EventStore:
    """Persist processed transactions so duplicate callbacks can be ignored safely.

    Uses ProcessedTransaction table as explicit idempotency ledger. This table tracks
    which webhook callbacks (identified by Daraja TransID) have been received and processed.
    The unique constraint on daraja_trans_id is the hard guarantee against race conditions â€”
    the already_processed() check is just an optimization to skip needless work, not the
    actual safety mechanism.
    """

    def __init__(self, database_url: Optional[str] = None, isolation_level: str = "serializable"):
        self.database_url = database_url or required_env("DATABASE_URL")
        self.isolation_level = isolation_level
        self.engine = None
        self.Session = None
        self._initialized = False
        self._init_lock = threading.Lock()

    def _ensure_ready(self) -> None:
        """Thread-safe lazy initialization of the database engine and session factory."""
        if self._initialized:
            return

        with self._init_lock:
            if self._initialized:
                return

            connect_args = {}
            if "postgresql" in self.database_url:
                connect_args["connect_timeout"] = int(os.getenv("DB_CONNECT_TIMEOUT", "5"))

            from database_service import create_database_engine

            self.engine = create_database_engine(
                self.database_url,
                isolation_level=self.isolation_level if "postgresql" in self.database_url else None,
                connect_args=connect_args,
            )
            # Production PostgreSQL schemas are owned by Alembic. SQLite is
            # retained as an explicit test/development convenience only.
            from observability import instrument_sqlalchemy_engine
            instrument_sqlalchemy_engine(self.engine)

            if self.database_url.startswith("sqlite"):
                Base.metadata.create_all(self.engine)
            self.Session = sessionmaker(bind=self.engine, expire_on_commit=False)
            self._initialized = True

    @tenant_scoped_argument()
    def already_processed(
        self,
        trans_id: str,
        tenant_id: Optional[str] = None,
        provider_account: Optional[str] = None,
        source_ip: Optional[str] = None,
    ) -> bool:
        """Check if a webhook callback has already been processed (idempotency gate).

        This is an optimization only â€” the real guarantee is the unique constraint
        enforced in mark_processed(). Conservative: returns True on DB errors, so a
        transient read failure doesn't cause reprocessing.

        Args:
            trans_id: Daraja M-Pesa TransID
            source_ip: Optional IP address of callback source (for audit)

        Returns:
            True if this callback has been seen before, or if the check itself
            failed (conservative fallback). False only on a confirmed "not seen".
        """
        if not trans_id:
            return False
        try:
            self._ensure_ready()
            with self.Session() as session:
                existing = session.query(ProcessedTransaction).filter(
                    ProcessedTransaction.daraja_trans_id == str(trans_id),
                    ProcessedTransaction.tenant_id == (tenant_id or "default"),
                    ProcessedTransaction.provider_account_id == (provider_account or "legacy-default"),
                ).first()
                return existing is not None
        except SQLAlchemyError:
            logger.exception(
                "already_processed() check failed for trans_id=%s â€” assuming processed "
                "(conservative fallback); mark_processed's unique constraint remains the "
                "real safety net.",
                trans_id,
            )
            return True

    @tenant_scoped_argument()
    def mark_processed(
        self,
        payload: Dict[str, Any],
        tenant_id: Optional[str] = None,
        source_ip: Optional[str] = None,
        signature_verified: bool = False,
        idempotency_key_override: Optional[str] = None,
        quality_processor: Optional[
            Callable[[Session, Dict[str, Any], str, str], Dict[str, Any]]
        ] = None,
    ) -> ProcessResult:
        """Atomically record that a webhook callback has been processed.

        Creates a ProcessedTransaction record with a unique constraint on
        daraja_trans_id. Distinguishes an expected duplicate (unique constraint
        violation) from a genuine error (connection failure, etc.) â€” callers must
        NOT treat these the same way.

        Args:
            payload: Daraja webhook payload dict
            tenant_id: Optional tenant identifier
            source_ip: Optional IP address of callback source
            signature_verified: Whether HMAC signature was valid

        Returns:
            ProcessResult.STORED    â€” new transaction, recorded successfully
            ProcessResult.DUPLICATE â€” already recorded, safe no-op
            ProcessResult.QUARANTINED â€” invalid financial input durably quarantined
            ProcessResult.ERROR     â€” genuine failure, caller should signal retry
        """
        trans_id = str(payload.get("TransID", "")).strip()
        tenant_id = str(tenant_id or "").strip()
        if not tenant_id:
            logger.error("mark_processed() rejected missing tenant for trans_id=%s", trans_id)
            return ProcessResult.ERROR
        account_id = provider_account_id(payload)
        idempotency_key = (idempotency_key_override or derive_idempotency_key(payload)).strip()
        if not idempotency_key or len(idempotency_key) > 255:
            logger.error("mark_processed() rejected invalid idempotency key for trans_id=%s", trans_id)
            return ProcessResult.ERROR
        try:
            currency = _resolve_currency(payload)
            amount = _money(payload.get("TransAmount", 0), currency)
            raw_provider = str(payload.get("provider") or "").strip()
            provider = _provider(raw_provider) if raw_provider else "mpesa"
        except ValueError as exc:
            logger.error("mark_processed() rejected trans_id=%s: %s", trans_id, exc)
            return ProcessResult.ERROR
        if not trans_id or not provider or provider == "unknown" or not account_id:
            logger.error("mark_processed() called with missing TransID in payload")
            return ProcessResult.ERROR
        request_hash = _payload_hash(payload)
        from logging_utils import get_observability_context
        from observability import extract_trace_context

        event_context = get_observability_context()
        candidate_traceparent = str(event_context.get("traceparent") or "").strip()
        event_metadata = {}
        if extract_trace_context({"traceparent": candidate_traceparent}):
            event_metadata["traceparent"] = candidate_traceparent
        event_correlation_id = str(
            payload.get("correlation_id")
            or event_context.get("correlation_id")
            or idempotency_key
        )
        event_payload = build_event(
            "transaction.received",
            tenant_id,
            trans_id,
            payload,
            event_id=idempotency_key,
            correlation_id=event_correlation_id,
            metadata=event_metadata,
        ).to_dict()
        event_payload["TransID"] = trans_id
        from logging_utils import bind_observability_context
        bind_observability_context(
            transaction_id=trans_id,
            event_id=event_payload["event_id"],
            correlation_id=event_payload["correlation_id"],
            tenant_id=tenant_id,
        )

        try:
            self._ensure_ready()
        except SQLAlchemyError:
            logger.exception("EventStore failed to initialize DB engine")
            return ProcessResult.ERROR

        try:
            with self.Session() as session:
                existing_result = _existing_request_result(
                    session,
                    tenant_id=tenant_id,
                    provider=provider,
                    idempotency_key=idempotency_key,
                    trans_id=trans_id,
                    request_hash=request_hash,
                )
                if existing_result is not None:
                    if existing_result is ProcessResult.DUPLICATE:
                        session.query(ProcessedTransaction).filter(
                            ProcessedTransaction.tenant_id == tenant_id,
                            ProcessedTransaction.provider_account_id == account_id,
                            ProcessedTransaction.daraja_trans_id == trans_id,
                        ).update(
                            {
                                ProcessedTransaction.webhook_attempt_number:
                                    ProcessedTransaction.webhook_attempt_number + 1
                            },
                            synchronize_session=False,
                        )
                        session.commit()
                    return existing_result
                if quality_processor is not None and provider == "mpesa":
                    try:
                        quality_decision = quality_processor(
                            session,
                            payload,
                            tenant_id,
                            provider,
                        )
                        if (
                            not isinstance(quality_decision, dict)
                            or quality_decision.get("decision") not in {"accepted", "quarantined"}
                        ):
                            raise ValueError("data-quality processor returned an invalid decision")
                    except Exception:
                        session.rollback()
                        logger.exception(
                            "Data-quality gate failed for tenant=%s trans_id=%s",
                            tenant_id,
                            trans_id,
                        )
                        return ProcessResult.ERROR
                    if quality_decision["decision"] == "quarantined":
                        session.commit()
                        quality_details = quality_decision.get("quality") or {}
                        logger.warning(
                            "Transaction durably quarantined by data-quality gate",
                            extra={
                                "tenant_id": tenant_id,
                                "trans_id": trans_id,
                                "quality_status": quality_details.get("status"),
                            },
                        )
                        return ProcessResult.QUARANTINED
                pt_record = ProcessedTransaction(
                    id=f"pt_{uuid.uuid4().hex[:12]}",
                    daraja_trans_id=trans_id,
                    tenant_id=tenant_id,
                    provider_account_id=account_id,
                    status="stored",
                    source_ip=source_ip,
                    signature_verified=signature_verified,
                    webhook_attempt_number=1,
                    received_at=datetime.now(timezone.utc),
                )
                session.add(pt_record)

                t_record = Transaction(
                    trans_id=trans_id,
                    tenant_id=tenant_id,
                    provider_account_id=account_id,
                    provider=provider,
                    idempotency_key=idempotency_key,
                    external_reference=str(payload.get("BillRefNumber") or payload.get("external_reference") or "").strip() or None,
                    provider_transaction_id=trans_id,
                    trans_amount=amount,
                    currency=currency,
                    msisdn=tokenize_identifier(payload.get("MSISDN", "")),
                    business_short_code=str(payload.get("BusinessShortCode", "")),
                    trans_time=str(payload.get("TransTime", "")),
                    raw_payload=protect_payload(payload),
                    raw_payload_hash=_source_payload_hash(payload),
                    source_schema_version=_source_schema_version(payload),
                    lifecycle_stage="STORED",
                    created_at=datetime.now(timezone.utc),
                )
                session.add(t_record)
                session.flush()
                session.add(TransactionEvent(
                    id=f"te_{idempotency_key[:24]}",
                    tenant_id=tenant_id,
                    transaction_id=t_record.id,
                    trans_id=trans_id,
                    event_key=f"{idempotency_key}:received",
                    event_type="transaction.received",
                    to_state="RECEIVED",
                    actor="event_store",
                    payload_hash=request_hash,
                    correlation_id=str(payload.get("correlation_id") or "") or None,
                    details={"provider_account_id": account_id},
                    created_at=datetime.now(timezone.utc),
                ))
                record_transformation_stage(
                    session,
                    tenant_id=tenant_id,
                    transaction_id=t_record.id,
                    event_id=event_payload["event_id"],
                    source_event_id=str(event_payload.get("source_event_id") or ""),
                    stage="RAW",
                    payload=payload.get("raw_payload") if isinstance(payload.get("raw_payload"), dict) else payload,
                )
                record_lineage(
                    session,
                    tenant_id=tenant_id,
                    transaction_id=trans_id,
                    event_id=event_payload["event_id"],
                    stage="RAW",
                    source=str(payload.get("source") or payload.get("provider") or "unknown"),
                    source_event_id=str(payload.get("source_event_id") or trans_id),
                    pipeline_version=os.getenv("PESAGUARD_PIPELINE_VERSION", "1"),
                    transformation_version="raw-1",
                    job_id=str(payload.get("job_id") or "") or None,
                    correlation_id=str(event_payload.get("correlation_id") or "") or None,
                )

                session.add(TransactionOutbox(
                    id=f"outbox_{uuid.uuid4().hex[:12]}",
                    tenant_id=tenant_id,
                    event_key=idempotency_key,
                    topic=os.getenv("KAFKA_TOPIC_TRANSACTIONS", "pesaguard.transactions.raw"),
                    payload=protect_payload(event_payload),
                    status="pending",
                    available_at=datetime.now(timezone.utc),
                    created_at=datetime.now(timezone.utc),
                ))
                session.add(IdempotencyRecord(
                    id=f"idem_{uuid.uuid4().hex[:12]}",
                    tenant_id=tenant_id,
                    provider=provider,
                    idempotency_key=idempotency_key,
                    external_reference=str(payload.get("BillRefNumber") or payload.get("external_reference") or "").strip() or None,
                    provider_transaction_id=trans_id,
                    request_hash=request_hash,
                ))
                session.add(AuditEvent(
                    id=f"audit_{uuid.uuid4().hex[:12]}",
                    tenant_id=tenant_id,
                    event_key=f"{idempotency_key}:accepted",
                    event_type="transaction.accepted",
                    aggregate_type="transaction",
                    aggregate_id=t_record.id,
                    actor="event_store",
                    payload_hash=request_hash,
                    details={"provider": provider, "provider_transaction_id": trans_id},
                ))

                from observability import trace_span
                with trace_span("db.transaction.persist", transaction_id=trans_id, tenant_id=tenant_id):
                    session.commit()
                from metrics import record_business_metric
                record_business_metric("transactions_received")
                return ProcessResult.STORED

        except IntegrityError:
            with self.Session() as session:
                existing_result = _existing_request_result(
                    session,
                    tenant_id=tenant_id,
                    provider=provider,
                    idempotency_key=idempotency_key,
                    trans_id=trans_id,
                    request_hash=request_hash,
                )
            if existing_result is not None:
                if existing_result is ProcessResult.DUPLICATE:
                    with self.Session() as session:
                        session.query(ProcessedTransaction).filter(
                            ProcessedTransaction.tenant_id == tenant_id,
                            ProcessedTransaction.provider_account_id == account_id,
                            ProcessedTransaction.daraja_trans_id == trans_id,
                        ).update(
                            {
                                ProcessedTransaction.webhook_attempt_number:
                                    ProcessedTransaction.webhook_attempt_number + 1
                            },
                            synchronize_session=False,
                        )
                        session.commit()
                logger.info(
                    "Concurrent idempotency replay resolved as %s for tenant=%s provider=%s key=%s",
                    existing_result.value,
                    tenant_id,
                    provider,
                    idempotency_key,
                )
                return existing_result
            if self.already_processed(trans_id, tenant_id=tenant_id, provider_account=account_id):
                logger.info(
                    "Duplicate webhook callback ignored for trans_id=%s idempotency_key=%s",
                    trans_id,
                    idempotency_key,
                )
                return ProcessResult.DUPLICATE
            logger.exception(
                "mark_processed() hit a non-duplicate integrity constraint for trans_id=%s idempotency_key=%s",
                trans_id,
                idempotency_key,
            )
            return ProcessResult.ERROR

        except SQLAlchemyError:
            logger.exception(
                "mark_processed() failed for trans_id=%s idempotency_key=%s due to a DB error, not a "
                "duplicate â€” this transaction was NOT stored and needs retry/investigation.",
                trans_id,
                idempotency_key,
            )
            return ProcessResult.ERROR

    @tenant_scoped_argument()
    def mark_processed_in_session(
        self,
        session: Session,
        payload: Dict[str, Any],
        tenant_id: Optional[str] = None,
        source_ip: Optional[str] = None,
        signature_verified: bool = False,
    ) -> ProcessResult:
        
        trans_id = str(payload.get("TransID", "")).strip()
        tenant_id = str(tenant_id or "").strip()
        if not tenant_id:
            logger.error("mark_processed_in_session() rejected missing tenant for trans_id=%s", trans_id)
            return ProcessResult.ERROR
        account_id = provider_account_id(payload)
        idempotency_key = derive_idempotency_key(payload)
        try:
            currency = _resolve_currency(payload)
            amount = _money(payload.get("TransAmount", 0), currency)
            raw_provider = str(payload.get("provider") or "").strip()
            provider = _provider(raw_provider) if raw_provider else "mpesa"
        except ValueError as exc:
            logger.error("mark_processed_in_session() rejected trans_id=%s: %s", trans_id, exc)
            return ProcessResult.ERROR
        if not trans_id or not provider or provider == "unknown" or not account_id:
            logger.error("mark_processed_in_session() called with missing TransID in payload")
            return ProcessResult.ERROR
        request_hash = _payload_hash(payload)
        from logging_utils import get_observability_context
        from observability import extract_trace_context

        event_context = get_observability_context()
        candidate_traceparent = str(event_context.get("traceparent") or "").strip()
        event_metadata = {}
        if extract_trace_context({"traceparent": candidate_traceparent}):
            event_metadata["traceparent"] = candidate_traceparent
        event_correlation_id = str(
            payload.get("correlation_id")
            or event_context.get("correlation_id")
            or idempotency_key
        )
        event_payload = build_event(
            "transaction.received",
            tenant_id,
            trans_id,
            payload,
            event_id=idempotency_key,
            correlation_id=event_correlation_id,
            metadata=event_metadata,
        ).to_dict()
        event_payload["TransID"] = trans_id

        existing_request_result = _existing_request_result(
            session,
            tenant_id=tenant_id,
            provider=provider,
            idempotency_key=idempotency_key,
            trans_id=trans_id,
            request_hash=request_hash,
        )
        if existing_request_result is not None:
            if existing_request_result is ProcessResult.DUPLICATE:
                existing = session.query(ProcessedTransaction).filter(
                    ProcessedTransaction.tenant_id == tenant_id,
                    ProcessedTransaction.provider_account_id == account_id,
                    ProcessedTransaction.daraja_trans_id == trans_id,
                ).one_or_none()
                if existing is not None:
                    existing.webhook_attempt_number += 1
            return existing_request_result

        existing = session.query(ProcessedTransaction).filter(
            ProcessedTransaction.daraja_trans_id == trans_id,
            ProcessedTransaction.tenant_id == tenant_id,
            ProcessedTransaction.provider_account_id == account_id,
        ).first()
        if existing is not None:
            existing.webhook_attempt_number += 1
            logger.info("Duplicate trans_id=%s detected in pre-flight session check", trans_id)
            return ProcessResult.DUPLICATE

        try:
            # Create a savepoint to catch duplicate constraints without breaking the parent transaction
            savepoint = session.begin_nested()

            pt_record = ProcessedTransaction(
                id=f"pt_{uuid.uuid4().hex[:12]}",
                daraja_trans_id=trans_id,
                tenant_id=tenant_id,
                provider_account_id=account_id,
                status="stored",
                source_ip=source_ip,
                signature_verified=signature_verified,
                webhook_attempt_number=1,
                received_at=datetime.now(timezone.utc),
            )
            session.add(pt_record)

            t_record = Transaction(
                trans_id=trans_id,
                tenant_id=tenant_id,
                provider_account_id=account_id,
                provider=provider,
                idempotency_key=idempotency_key,
                external_reference=str(payload.get("BillRefNumber") or payload.get("external_reference") or "").strip() or None,
                provider_transaction_id=trans_id,
                trans_amount=amount,
                currency=currency,
                msisdn=tokenize_identifier(payload.get("MSISDN", "")),
                business_short_code=str(payload.get("BusinessShortCode", "")),
                trans_time=str(payload.get("TransTime", "")),
                raw_payload=protect_payload(payload),
                raw_payload_hash=_source_payload_hash(payload),
                source_schema_version=_source_schema_version(payload),
                lifecycle_stage="STORED",
                created_at=datetime.now(timezone.utc),
            )
            session.add(t_record)
            session.flush()
            session.add(TransactionEvent(
                id=f"te_{idempotency_key[:24]}",
                tenant_id=tenant_id,
                transaction_id=t_record.id,
                trans_id=trans_id,
                event_key=f"{idempotency_key}:received",
                event_type="transaction.received",
                to_state="RECEIVED",
                actor="event_store",
                payload_hash=request_hash,
                correlation_id=str(payload.get("correlation_id") or "") or None,
                details={"provider_account_id": account_id},
                created_at=datetime.now(timezone.utc),
            ))

            session.add(TransactionOutbox(
                id=f"outbox_{uuid.uuid4().hex[:12]}",
                tenant_id=tenant_id,
                event_key=idempotency_key,
                topic=os.getenv("KAFKA_TOPIC_TRANSACTIONS", "pesaguard.transactions.raw"),
                payload=protect_payload(event_payload),
                status="pending",
                available_at=datetime.now(timezone.utc),
                created_at=datetime.now(timezone.utc),
            ))
            session.add(IdempotencyRecord(
                id=f"idem_{uuid.uuid4().hex[:12]}",
                tenant_id=tenant_id,
                provider=provider,
                idempotency_key=idempotency_key,
                external_reference=str(payload.get("BillRefNumber") or payload.get("external_reference") or "").strip() or None,
                provider_transaction_id=trans_id,
                request_hash=request_hash,
            ))
            session.add(AuditEvent(
                id=f"audit_{uuid.uuid4().hex[:12]}",
                tenant_id=tenant_id,
                event_key=f"{idempotency_key}:accepted",
                event_type="transaction.accepted",
                aggregate_type="transaction",
                aggregate_id=t_record.id,
                actor="event_store",
                payload_hash=request_hash,
                details={"provider": provider, "provider_transaction_id": trans_id},
            ))

            session.flush()
            return ProcessResult.STORED

        except IntegrityError:
            savepoint.rollback()
            existing_request_result = _existing_request_result(
                session,
                tenant_id=tenant_id,
                provider=provider,
                idempotency_key=idempotency_key,
                trans_id=trans_id,
                request_hash=request_hash,
            )
            if existing_request_result is not None:
                return existing_request_result
            existing = session.query(ProcessedTransaction).filter(
                ProcessedTransaction.daraja_trans_id == trans_id,
                ProcessedTransaction.tenant_id == tenant_id,
                ProcessedTransaction.provider_account_id == account_id,
            ).first()
            if existing is not None:
                logger.info(
                    "Duplicate trans_id=%s idempotency_key=%s caught at flush time (race window closed by unique constraint)",
                    trans_id,
                    idempotency_key,
                )
                return ProcessResult.DUPLICATE
            logger.exception(
                "mark_processed_in_session() hit a non-duplicate integrity constraint for trans_id=%s idempotency_key=%s",
                trans_id,
                idempotency_key,
            )
            return ProcessResult.ERROR
        except SQLAlchemyError:
            savepoint.rollback()
            logger.exception(
                "mark_processed_in_session() encountered database error for trans_id=%s idempotency_key=%s",
                trans_id,
                idempotency_key,
            )
            return ProcessResult.ERROR

    def update_processing_status(
        self,
        trans_id: str,
        status: str,
        tenant_id: Optional[str] = None,
        provider_account: Optional[str] = None,
        error_reason: Optional[str] = None,
        processing_time_ms: Optional[int] = None,
    ) -> None:
        """Update the processing status of a webhook callback."""
        if not trans_id:
            return

        try:
            self._ensure_ready()
            with self.Session() as session:
                pt_record = session.query(ProcessedTransaction).filter(
                    ProcessedTransaction.daraja_trans_id == str(trans_id),
                    ProcessedTransaction.tenant_id == (tenant_id or "default"),
                    ProcessedTransaction.provider_account_id == (provider_account or "legacy-default"),
                ).first()
                if pt_record:
                    pt_record.status = status
                    if error_reason:
                        pt_record.error_reason = error_reason
                    if processing_time_ms is not None:
                        pt_record.processing_time_ms = processing_time_ms
                    session.commit()
                else:
                    logger.warning("update_processing_status() found no ProcessedTransaction for trans_id=%s", trans_id)
        except SQLAlchemyError:
            logger.exception("update_processing_status() failed for trans_id=%s", trans_id)

    def transition_transaction(
        self,
        trans_id: str,
        target: str,
        tenant_id: str,
        expected_version: int,
        actor: str = "system",
        reason: Optional[str] = None,
    ) -> bool:
        """Advance a transaction only when its state and version are current."""
        self._ensure_ready()
        with self.Session() as session:
            row = session.query(Transaction).filter(
                Transaction.trans_id == str(trans_id),
                Transaction.tenant_id == tenant_id,
                Transaction.version == expected_version,
            ).one_or_none()
            if row is None:
                return False
            try:
                new_state = transition_transaction(row.status, target)
            except ValueError:
                return False
            old_state = row.status
            row.status = new_state
            row.version += 1
            session.add(TransactionEvent(
                id=f"te_{uuid.uuid4().hex[:12]}",
                tenant_id=tenant_id,
                transaction_id=row.id,
                trans_id=str(trans_id),
                event_key=f"{row.id}:state:{row.version}",
                event_type="transaction.state_changed",
                from_state=old_state,
                to_state=new_state,
                actor=actor,
                reason=reason,
                details={"version": row.version},
            ))
            try:
                session.commit()
            except SQLAlchemyError:
                session.rollback()
                return False
            return True

    def mark_transaction_archived(self, transaction_id: str, tenant_id: str, object_key: str) -> bool:
        """Mark a transaction archived only after its immutable archive is written."""
        self._ensure_ready()
        with self.Session() as session:
            row = session.query(Transaction).filter(
                Transaction.id == transaction_id,
                Transaction.tenant_id == tenant_id,
            ).one_or_none()
            if row is None:
                return False
            try:
                transition_data_lifecycle(row.lifecycle_stage, "ARCHIVED")
            except ValueError:
                session.rollback()
                return False
            row.lifecycle_stage = "ARCHIVED"
            row.archive_object_key = object_key
            row.archived_at = datetime.now(timezone.utc)
            session.commit()
            return True

    @tenant_scoped_argument()
    def write_dead_letter(
        self,
        payload: Optional[Dict[str, Any]],
        reason: str,
        error_detail: Optional[str] = None,
        tenant_id: Optional[str] = None,
    ) -> bool:
        """Persist a malformed or rejected webhook payload for later inspection and replay."""
        from models import DeadLetter

        scoped_tenant = str(tenant_id or "").strip()
        if not scoped_tenant:
            logger.error("write_dead_letter() rejected missing tenant context")
            return False
        raw_payload = payload or {}
        canonical_payload = json.dumps(raw_payload, sort_keys=True, separators=(",", ":"), default=str)
        payload_hash = hashlib.sha256(canonical_payload.encode("utf-8")).hexdigest()
        account_id = provider_account_id(raw_payload)
        trans_id = str(raw_payload.get("TransID") or raw_payload.get("trans_id") or "unknown")
        event_key = hashlib.sha256(
            f"{scoped_tenant}:{account_id}:{trans_id}:{reason}:{payload_hash}".encode("utf-8")
        ).hexdigest()
        try:
            self._ensure_ready()
            with self.Session() as session:
                duplicate = session.query(DeadLetter.id).filter(
                    DeadLetter.tenant_id == scoped_tenant,
                    DeadLetter.event_key == event_key,
                ).first()
                if duplicate is not None:
                    return True
                dl = DeadLetter(
                    id=f"dl_{event_key[:24]}",
                    tenant_id=scoped_tenant,
                    reason=reason,
                    payload=protect_payload(raw_payload),
                    error_detail=str(error_detail)[:2000] if error_detail else None,
                    attempts=0,
                    processed=False,
                    processed_at=None,
                    replay_status="idle",
                    provider_account_id=account_id,
                    event_key=event_key,
                    created_at=datetime.now(timezone.utc),
                )
                session.add(dl)
                session.commit()
                logger.info("Recorded dead-letter entry id=%s reason=%s", dl.id, reason)
                return True
        except IntegrityError:
            with self.Session() as session:
                duplicate = session.query(DeadLetter.id).filter(
                    DeadLetter.tenant_id == scoped_tenant,
                    DeadLetter.event_key == event_key,
                ).first()
                if duplicate is not None:
                    return True
            logger.exception("write_dead_letter() could not persist reason=%s", reason)
            return False
        except SQLAlchemyError:
            logger.exception(
                "write_dead_letter() failed for reason=%s â€” payload could not be persisted.",
                reason,
            )
            return False

    @tenant_scoped_argument()
    def claim_outbox_batch(self, limit: int = 100, lease_seconds: int = 60, tenant_id: str | None = None):
        """Claim a bounded batch of pending outbox rows for one worker lease."""
        self._ensure_ready()
        now = datetime.now(timezone.utc)
        lease_until = now + timedelta(seconds=lease_seconds)
        with self.Session() as session:
            query = (
                session.query(TransactionOutbox)
                .filter(
                    TransactionOutbox.status.in_(["pending", "failed"]),
                    TransactionOutbox.available_at <= now,
                    or_(TransactionOutbox.locked_until.is_(None), TransactionOutbox.locked_until < now),
                )
                .order_by(TransactionOutbox.created_at.asc())
                .with_for_update(skip_locked=True)
            )
            if tenant_id:
                query = query.filter(TransactionOutbox.tenant_id == tenant_id)
            rows = query.limit(max(1, min(limit, 1000))).all()
            for row in rows:
                row.status = "processing"
                row.attempts += 1
                row.locked_until = lease_until
            session.commit()
            return [
                {
                    "id": row.id,
                    "tenant_id": row.tenant_id,
                    "topic": row.topic,
                    "payload": row.payload,
                    "attempts": row.attempts,
                }
                for row in rows
            ]

    @tenant_scoped_argument()
    def mark_outbox_published(self, outbox_id: str, tenant_id: str | None = None) -> None:
        """Mark a successfully published outbox row."""
        self._ensure_ready()
        with self.Session() as session:
            query = session.query(TransactionOutbox).filter(TransactionOutbox.id == outbox_id)
            if tenant_id is not None:
                query = query.filter(TransactionOutbox.tenant_id == tenant_id)
            row = query.one_or_none()
            if row is None:
                return
            row.status = "published"
            row.published_at = datetime.now(timezone.utc)
            row.locked_until = None
            session.commit()

    @tenant_scoped_argument()
    def mark_outbox_failed(self, outbox_id: str, error: str, retry_seconds: int = 30, tenant_id: str | None = None) -> None:
        """Release a failed outbox row with bounded retry backoff."""
        self._ensure_ready()
        with self.Session() as session:
            query = session.query(TransactionOutbox).filter(TransactionOutbox.id == outbox_id)
            if tenant_id is not None:
                query = query.filter(TransactionOutbox.tenant_id == tenant_id)
            row = query.one_or_none()
            if row is None:
                return
            row.status = "failed"
            row.last_error = str(error)[:1000]
            row.available_at = datetime.now(timezone.utc) + timedelta(seconds=min(max(retry_seconds, 1), 3600))
            row.locked_until = None
            session.commit()

    @tenant_scoped_argument()
    def mark_outbox_dead_lettered(self, outbox_id: str, error: str, tenant_id: str | None = None) -> None:
        """Atomically stop retrying an exhausted outbox row and record it for replay."""
        from models import DeadLetter

        self._ensure_ready()
        with self.Session() as session:
            query = session.query(TransactionOutbox).filter(TransactionOutbox.id == outbox_id)
            if tenant_id is not None:
                query = query.filter(TransactionOutbox.tenant_id == tenant_id)
            row = query.one_or_none()
            if row is None or row.status == "dead_lettered":
                return

            dead_letter_id = f"dl_outbox_{outbox_id}"
            dead_letter = session.get(DeadLetter, dead_letter_id)
            if dead_letter is None:
                dead_letter = DeadLetter(
                    id=dead_letter_id,
                    tenant_id=row.tenant_id,
                    reason="transaction_outbox_publish_exhausted",
                    payload=row.payload,
                    error_detail=str(error)[:2000],
                    attempts=row.attempts,
                    processed=False,
                    replay_status="idle",
                    event_key=row.event_key,
                    created_at=datetime.now(timezone.utc),
                )
                session.add(dead_letter)

            row.status = "dead_lettered"
            row.last_error = str(error)[:1000]
            row.locked_until = None
            session.commit()

    @tenant_scoped_argument()
    def claim_reconciliation_outbox_batch(
        self,
        limit: int = 100,
        lease_seconds: int = 60,
        tenant_id: str | None = None,
    ):
        """Claim due reconciliation publications with an expiring lease."""
        self._ensure_ready()
        now = datetime.now(timezone.utc)
        lease_until = now + timedelta(seconds=lease_seconds)
        with self.Session() as session:
            query = (
                session.query(ReconciliationOutbox)
                .filter(
                    ReconciliationOutbox.status.in_(["pending", "failed"]),
                    ReconciliationOutbox.available_at <= now,
                    or_(ReconciliationOutbox.locked_until.is_(None), ReconciliationOutbox.locked_until < now),
                )
                .order_by(ReconciliationOutbox.created_at.asc())
                .with_for_update(skip_locked=True)
            )
            if tenant_id:
                query = query.filter(ReconciliationOutbox.tenant_id == tenant_id)
            rows = query.limit(max(1, min(limit, 1000))).all()
            for row in rows:
                row.status = "processing"
                row.attempts += 1
                row.locked_until = lease_until
            session.commit()
            return [
                {"id": row.id, "tenant_id": row.tenant_id, "event_key": row.event_key,
                 "topic": row.topic, "payload": row.payload, "attempts": row.attempts}
                for row in rows
            ]

    @tenant_scoped_argument()
    def mark_reconciliation_outbox_published(self, outbox_id: str, tenant_id: str | None = None) -> None:
        """Mark a successfully published reconciliation row."""
        self._ensure_ready()
        with self.Session() as session:
            query = session.query(ReconciliationOutbox).filter(
                ReconciliationOutbox.id == outbox_id
            )
            if tenant_id is not None:
                query = query.filter(ReconciliationOutbox.tenant_id == tenant_id)
            row = query.one_or_none()
            if row is None:
                return
            row.status = "published"
            row.published_at = datetime.now(timezone.utc)
            row.locked_until = None
            session.commit()

    @tenant_scoped_argument()
    def mark_reconciliation_outbox_failed(
        self,
        outbox_id: str,
        error: str,
        retry_seconds: int = 30,
        tenant_id: str | None = None,
    ) -> None:
        """Release a failed reconciliation row for a later retry."""
        self._ensure_ready()
        with self.Session() as session:
            query = session.query(ReconciliationOutbox).filter(
                ReconciliationOutbox.id == outbox_id
            )
            if tenant_id is not None:
                query = query.filter(ReconciliationOutbox.tenant_id == tenant_id)
            row = query.one_or_none()
            if row is None:
                return
            row.status = "failed"
            row.last_error = str(error)[:1000]
            row.available_at = datetime.now(timezone.utc) + timedelta(seconds=min(max(retry_seconds, 1), 3600))
            row.locked_until = None
            session.commit()

    def list_active_tenants(self) -> list[str]:
        """Enumerate tenants through the isolated registry-only worker role."""
        from tenant_registry import list_active_tenant_ids

        return list_active_tenant_ids()


# Default singleton instance
event_store = EventStore()
