"""
Reconciliation Job (Production Kafka Consumer Service)

Processes canonical transaction events from `pesaguard.transactions.raw`, executes multi-tenant
reconciliation checks against connector internal records, atomically records idempotency marks
and audit entries in Postgres, and commits consumer offsets safely.
"""

from __future__ import annotations

from environment import required_env

import json
import hashlib
import base64
from decimal import Decimal
import logging
import os
import signal
import sys
import threading
import time
from contextlib import contextmanager
from datetime import datetime, timezone
from typing import Any, Dict, Optional, Set

from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

try:
    from kafka import KafkaConsumer, KafkaProducer
    HAS_KAFKA = True
except ImportError:
    KafkaConsumer = None  # type: ignore[assignment]
    KafkaProducer = None  # type: ignore[assignment]
    HAS_KAFKA = False

from action_audit import ActionAuditRecord, persist_audit_event
from anomaly_rules import check_for_anomalies
from base_connector import ConnectorRegistry
from event_store import EventStore, ProcessResult, provider_account_id
from fraud_risk_engine import assess_transaction, persist_assessment
from logging_utils import configure_logging
from normalization import (
    DEFAULT_CURRENCY,
    NormalizationError,
    currency_quantum,
    normalize_amount,
    normalize_currency,
)
from models import Base, Discrepancy, ProcessedTransaction, ReconciliationMatch, ReconciliationOutbox, Transaction
from reconciliation_engine import ReconciliationEngine
from tenant_settings import TenantSettingsStore
from communications.events import discrepancy_notification_event
from background_tasks import enqueue_notification_event
from lifecycle import transition_reconciliation

configure_logging()
logger = logging.getLogger("pesaguard.reconciliation")

from tenant_context import tenant_scope, tenant_scoped_argument

# Kafka & Service Settings
KAFKA_BOOTSTRAP_SERVERS = os.getenv("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092")
TOPIC_RAW = os.getenv("KAFKA_TOPIC_TRANSACTIONS", "pesaguard.transactions.raw")
TOPIC_MATCHED = os.getenv("KAFKA_TOPIC_MATCHED", "pesaguard.reconciliation.completed")
TOPIC_DISCREPANCIES = os.getenv("KAFKA_TOPIC_DISCREPANCIES", "pesaguard.reconciliation.exceptions")
UNATTRIBUTED_TENANT_ID = os.getenv("PESAGUARD_UNATTRIBUTED_TENANT_ID", "pesaguard-unattributed")
WINDOW_MINUTES = int(os.getenv("RECONCILIATION_WINDOW_MINUTES", "15"))
BATCH_SIZE = max(1, int(os.getenv("PESAGUARD_RECONCILIATION_BATCH_SIZE", "100")))
MAX_TENANT_IN_FLIGHT = max(1, int(os.getenv("PESAGUARD_MAX_TENANT_IN_FLIGHT", "4")))
DEAD_LETTER_RETRY_SECONDS = 5.0

# Liveness heartbeat for the Docker healthcheck (PesaGuard Rule #40). The
# reconciler is a Kafka consumer with no HTTP port, so the loop touches this
# file each iteration and Compose fails the container when it goes stale (e.g.
# broker wedge, deadlock). Path is env-overridable for tests; heartbeat I/O
# failures are swallowed so monitoring can never break reconciliation (Rule #57).
HEARTBEAT_FILE = os.getenv(
    "PESAGUARD_RECONCILIATION_HEARTBEAT_FILE",
    "/tmp/pesaguard-reconciliation.heartbeat",
)
HEARTBEAT_STALE_SECONDS = max(1, int(os.getenv("PESAGUARD_RECONCILIATION_HEARTBEAT_STALE_SECONDS", "120")))


def _touch_heartbeat() -> None:
    """Record loop liveness; never raise (observability must not break work)."""
    try:
        with open(HEARTBEAT_FILE, "a", encoding="utf-8"):
            os.utime(HEARTBEAT_FILE, None)
    except OSError:
        logger.debug("Reconciliation heartbeat touch failed", exc_info=True)

DB_URL = required_env("DATABASE_URL")

# Database Engine & Event Store Setup
if DB_URL.startswith("sqlite"):
    engine = create_engine(DB_URL, connect_args={"check_same_thread": False})
else:
    engine = create_engine(DB_URL, pool_pre_ping=True, pool_size=5, max_overflow=10)
from metrics import instrument_engine_query_timing
instrument_engine_query_timing(engine)
engine_for_audit = engine
AuditSession = sessionmaker(bind=engine, expire_on_commit=False)
event_store = EventStore(database_url=DB_URL)
settings_store = TenantSettingsStore()
reconciliation_engine = ReconciliationEngine(window_seconds=WINDOW_MINUTES * 60)

_RUNNING = True
_PAUSED_PARTITIONS: Dict[Any, float] = {}


class _MessageRetryRequired(RuntimeError):
    """A durable outcome or Kafka offset commit must be retried before progress."""


class TenantConcurrencyLimiter:
    """Bound reconciliation work per tenant to protect shared resources."""

    def __init__(self, limit: int):
        self.limit = limit
        self._lock = threading.Lock()
        self._semaphores: Dict[str, threading.BoundedSemaphore] = {}

    @contextmanager
    def acquire(self, tenant_id: str):
        with self._lock:
            semaphore = self._semaphores.setdefault(tenant_id, threading.BoundedSemaphore(self.limit))
        semaphore.acquire()
        try:
            yield
        finally:
            semaphore.release()


tenant_limiter = TenantConcurrencyLimiter(MAX_TENANT_IN_FLIGHT)


def _signal_handler(signum, frame):
    """Graceful shutdown signal listener for containerized deployments."""
    global _RUNNING
    logger.info("Received termination signal (%s). Initiating graceful shutdown...", signum)
    _RUNNING = False


signal.signal(signal.SIGINT, _signal_handler)
signal.signal(signal.SIGTERM, _signal_handler)


def dispatch_discrepancy_alert(evaluation: Dict[str, Any], tenant_id: Optional[str] = None) -> Dict[str, Any]:
    """Queue multi-channel alerts without calling communication providers in reconciliation."""
    if evaluation.get("status") not in {"needs_review", "missing_payment"} and not evaluation.get("anomalies"):
        return {"status": "skipped", "trans_id": evaluation.get("trans_id")}

    tenant_id = tenant_id or evaluation.get("tenant_id")
    if not isinstance(tenant_id, str) or not tenant_id.strip():
        raise ValueError("discrepancy notification requires a tenant_id")
    with tenant_scope(tenant_id.strip()):
        return enqueue_notification_event(
            discrepancy_notification_event(evaluation, tenant_id.strip())
        )


@tenant_scoped_argument()
def _persist_atomically(event: Dict[str, Any], evaluation: Dict[str, Any], trans_id: str, tenant_id: str) -> ProcessResult:
    """
    Atomically commit the idempotency ledger entry and audit log record
    within a SINGLE database transaction.
    """
    phase3_status = evaluation.get("phase3_status")
    evidence = evaluation.get("evidence")
    required_evidence = {
        "matching_rules",
        "match_score",
        "match_timestamp",
        "engine_version",
        "rule_version",
    }
    valid_statuses = {"MATCHED", "UNMATCHED", "PARTIAL", "MISMATCH", "DUPLICATE", "PENDING", "EXCEPTION"}
    if (
        phase3_status not in valid_statuses
        or not isinstance(evidence, dict)
        or not required_evidence.issubset(evidence)
        or not isinstance(evidence.get("matching_rules"), list)
        or not evidence.get("engine_version")
        or not evidence.get("rule_version")
    ):
        logger.error("Refusing to persist a reconciliation without a complete, versioned decision for trans_id=%s", trans_id)
        return ProcessResult.ERROR
    try:
        match_timestamp = datetime.fromisoformat(evidence["match_timestamp"])
        match_score = float(evidence["match_score"])
        if not 0 <= match_score <= 1:
            raise ValueError("match score must be between 0 and 1")
    except (TypeError, ValueError) as exc:
        logger.error("Refusing to persist invalid reconciliation evidence for trans_id=%s: %s", trans_id, exc)
        return ProcessResult.ERROR

    session = AuditSession()
    try:
        provider_id = provider_account_id(event)
        event_key = hashlib.sha256(f"{tenant_id}:{provider_id}:{trans_id}".encode("utf-8")).hexdigest()
        processed = session.query(ProcessedTransaction).filter(
            ProcessedTransaction.daraja_trans_id == trans_id,
            ProcessedTransaction.tenant_id == tenant_id,
            ProcessedTransaction.provider_account_id == provider_id,
        ).with_for_update().first()
        if processed is None:
            processed = ProcessedTransaction(
                id=f"pt_{hashlib.sha256(event_key.encode()).hexdigest()[:12]}",
                daraja_trans_id=trans_id,
                tenant_id=tenant_id,
                provider_account_id=provider_id,
                status="received",
                received_at=datetime.now(timezone.utc),
            )
            session.add(processed)
        elif processed.reconciliation_status == "completed":
            session.rollback()
            return ProcessResult.DUPLICATE

        transition_reconciliation(processed.reconciliation_status or "pending", "processing")
        processed.reconciliation_status = "processing"
        processed.reconciliation_attempts = (processed.reconciliation_attempts or 0) + 1
        processed.reconciliation_started_at = datetime.now(timezone.utc)
        processed.reconciliation_error = None
        session.flush()
        result_payload = json.loads(json.dumps(evaluation, ensure_ascii=False, default=str))
        evidence = result_payload["evidence"]

        recent_transactions = session.query(Transaction).filter(
            Transaction.tenant_id == tenant_id,
            Transaction.provider_account_id == provider_id,
            Transaction.trans_id != trans_id,
        ).order_by(Transaction.created_at.desc()).limit(100).all()
        risk_history = [
            {
                "TransID": item.trans_id,
                "TransAmount": item.trans_amount,
                "Currency": item.currency,
                "MSISDN": item.msisdn,
                "BusinessShortCode": item.business_short_code,
                "TransTime": item.trans_time,
            }
            for item in recent_transactions
        ]
        risk_decision = assess_transaction(event, risk_history)
        persist_assessment(session, tenant_id, trans_id, risk_decision)
        result_payload["fraud_risk"] = risk_decision.as_dict()

        phase3_status = evaluation.get("phase3_status")
        is_discrepancy = (
            phase3_status not in {None, "MATCHED", "DUPLICATE"}
            if phase3_status
            else evaluation.get("status") in {"needs_review", "missing_payment"} or bool(evaluation.get("anomalies"))
        )
        if risk_decision.risk_level in {"HIGH", "CRITICAL"}:
            is_discrepancy = True
            result_payload["anomalies"] = list(result_payload.get("anomalies") or []) + list(risk_decision.reason_codes)
            result_payload["severity"] = (
                "critical"
                if risk_decision.risk_level == "CRITICAL" or result_payload.get("severity") == "critical"
                else "warning"
            )
        if is_discrepancy:
            discrepancy_id = f"{tenant_id}:{trans_id}:reconciliation"
            discrepancy = session.get(Discrepancy, discrepancy_id)
            if discrepancy is None:
                discrepancy_status = evaluation.get("status")
                if discrepancy_status not in {"needs_review", "reviewed", "resolved", "escalated"}:
                    discrepancy_status = "needs_review"
                discrepancy = Discrepancy(
                    id=discrepancy_id,
                    trans_id=trans_id,
                    tenant_id=tenant_id,
                    anomaly_type=(
                        "fraud_risk"
                        if risk_decision.risk_level in {"HIGH", "CRITICAL"}
                        and phase3_status in {"MATCHED", "DUPLICATE"}
                        else evaluation.get("status", "reconciliation_anomaly")
                    ),
                    status=discrepancy_status,
                    severity=result_payload.get("severity", "warning"),
                    details=json.dumps(result_payload, ensure_ascii=False),
                    detected_at=datetime.now(timezone.utc),
                )
                session.add(discrepancy)

        audit_details = {
            "trans_id": trans_id,
            "status": evaluation.get("status"),
            "phase3_status": phase3_status,
            "rule_version": evidence["rule_version"],
            "evidence": evidence,
            "match": result_payload.get("match"),
            "anomalies": result_payload.get("anomalies", []),
        }
        trace_context = {}
        try:
            from logging_utils import get_observability_context
            trace_context = get_observability_context()
        except Exception:
            logger.debug("Could not read trace identifiers for reconciliation audit", exc_info=True)
        persist_audit_event(session, ActionAuditRecord(
            tenant_id=tenant_id,
            actor="reconciliation_job",
            action="discrepancy_flagged" if is_discrepancy else "matched",
            category="operations",
            outcome="success",
            severity=result_payload.get("severity", "info"),
            resource_type="transaction",
            resource_id=trans_id,
            idempotency_key=f"reconciliation:{event_key}",
            details=audit_details,
            trace_id=trace_context.get("trace_id") or None,
            correlation_id=trace_context.get("correlation_id") or None,
            request_id=trace_context.get("request_id") or None,
        ))
        matched_record = evidence.get("matched_record")
        if evidence.get("matched_records"):
            matched_record = {
                "match_type": "one_to_many",
                "records": evidence["matched_records"],
                "internal_refs": evidence.get("matched_internal_refs", []),
            }
        session.add(ReconciliationMatch(
            id=f"recon_match_{event_key[:24]}",
            tenant_id=tenant_id,
            transaction_id=trans_id,
            matched_record=matched_record,
            matching_rules=evidence["matching_rules"],
            match_score=match_score,
            match_timestamp=match_timestamp,
            engine_version=evidence["engine_version"],
            status=phase3_status,
            processing_latency_ms=evaluation.get("processing_latency_ms", 0),
            created_at=datetime.now(timezone.utc),
        ))
        result_topic = TOPIC_DISCREPANCIES if is_discrepancy else TOPIC_MATCHED
        session.add(ReconciliationOutbox(
            id=f"recon_outbox_{event_key[:12]}",
            tenant_id=tenant_id,
            event_key=event_key,
            topic=result_topic,
            payload=result_payload,
            status="pending",
            created_at=datetime.now(timezone.utc),
            available_at=datetime.now(timezone.utc),
        ))
        session.flush()
        transition_reconciliation(processed.reconciliation_status, "completed")
        processed.reconciliation_status = "completed"
        processed.reconciliation_completed_at = datetime.now(timezone.utc)
        session.commit()
        return ProcessResult.STORED

    except Exception as exc:
        logger.exception("Atomic persist transaction failed for trans_id=%s — rolling back: %s", trans_id, exc)
        session.rollback()
        return ProcessResult.ERROR
    finally:
        session.close()


def _publish_downstream(evaluation: Dict[str, Any], trans_id: str, producer: Any, tenant_id: str) -> None:
    """Best-effort publish of reconciliation results to downstream Kafka topics."""
    provider_id = provider_account_id(evaluation.get("event", {}))
    event_key = hashlib.sha256(f"{tenant_id}:{provider_id}:{trans_id}".encode("utf-8")).hexdigest()
    session = None
    outbox = None

    try:
        session = AuditSession()
        outbox = session.query(ReconciliationOutbox).filter_by(tenant_id=tenant_id, event_key=event_key).first()
        if outbox is None or outbox.status == "published":
            session.close()
            return
        topic = outbox.topic
        is_discrepancy = topic == TOPIC_DISCREPANCIES
        outbox.status = "processing"
        outbox.attempts = (outbox.attempts or 0) + 1
        session.commit()
        key_bytes = str(trans_id).encode("utf-8")
        val_bytes = json.dumps(outbox.payload, ensure_ascii=False).encode("utf-8")

        try:
            future = producer.send(topic, key=key_bytes, value=val_bytes)
        except TypeError:
            future = producer.send(topic, val_bytes)

        if hasattr(producer, "flush"):
            producer.flush(timeout=5)
        if hasattr(future, "get"):
            future.get(timeout=5)

        if is_discrepancy:
            logger.warning("Discrepancy event published for trans_id=%s to topic=%s", trans_id, topic)
            dispatch_result = dispatch_discrepancy_alert(
                {**evaluation, **outbox.payload, "event_id": event_key},
                tenant_id=tenant_id,
            )
            if not isinstance(dispatch_result, dict) or dispatch_result.get("status") not in {"queued", "skipped"}:
                status = dispatch_result.get("status") if isinstance(dispatch_result, dict) else "invalid_result"
                raise RuntimeError(f"notification event enqueue did not succeed: {status}")
        else:
            logger.info("Transaction %s cleanly reconciled and published to %s", trans_id, topic)
        outbox.status = "published"
        outbox.published_at = datetime.now(timezone.utc)
        outbox.locked_until = None
        session.commit()
        session.close()

    except Exception as exc:
        try:
            if outbox is None or session is None:
                raise RuntimeError("reconciliation outbox state was not initialized")
            outbox.status = "failed"
            outbox.last_error = str(exc)[:1000]
            outbox.available_at = datetime.now(timezone.utc)
            outbox.locked_until = None
            session.commit()
            session.close()
        except Exception:
            logger.exception("Failed recording reconciliation outbox failure for trans_id=%s", trans_id)
        logger.exception(
            "Failed publishing trans_id=%s to downstream topic=%s. DB record remains authoritative.",
            trans_id, topic
        )


def publish_reconciliation_outbox_batch(producer: Any, limit: int = 100) -> Dict[str, int]:
    """Publish leased reconciliation outbox rows and persist retryable outcomes."""
    published = 0
    failed = 0
    remaining = max(1, limit)
    event_store._ensure_ready()
    tenant_ids = (
        event_store.list_active_tenants()
        if event_store.engine.dialect.name == "postgresql"
        else [None]
    )
    for tenant_id in tenant_ids:
        kwargs = {"tenant_id": tenant_id} if tenant_id is not None else {}
        rows = event_store.claim_reconciliation_outbox_batch(limit=remaining, **kwargs)
        for row in rows:
            try:
                try:
                    future = producer.send(row["topic"], key=row["event_key"].encode("utf-8"),
                                           value=json.dumps(row["payload"], ensure_ascii=False).encode("utf-8"))
                except TypeError:
                    future = producer.send(row["topic"], json.dumps(row["payload"], ensure_ascii=False).encode("utf-8"))
                if hasattr(producer, "flush"):
                    producer.flush(timeout=5)
                if hasattr(future, "get"):
                    future.get(timeout=5)
                event_store.mark_reconciliation_outbox_published(row["id"], tenant_id=row["tenant_id"])
                published += 1
            except Exception as exc:
                event_store.mark_reconciliation_outbox_failed(row["id"], str(exc), tenant_id=row["tenant_id"])
                failed += 1
                logger.exception("Failed publishing reconciliation outbox id=%s", row["id"])
        remaining -= len(rows)
        if remaining <= 0:
            break
    return {"published": published, "failed": failed}


def _process_message(event: Dict[str, Any], consumer: Any, producer: Any, connector_registry: Any) -> bool:
    tenant_id = _trusted_message_tenant(event)
    with tenant_limiter.acquire(tenant_id):
        return _process_message_unbounded(event, consumer, producer, connector_registry)


def _trusted_message_tenant(event: Dict[str, Any]) -> str:
    tenant_id = event.get("tenant_id") or event.get("TenantID")
    if not isinstance(tenant_id, str) or not tenant_id.strip():
        raise ValueError("Kafka transaction message is missing a tenant_id")
    tenant_id = tenant_id.strip()
    with tenant_scope(tenant_id):
        return tenant_id


def _process_message_unbounded(event: Dict[str, Any], consumer: Any, producer: Any, connector_registry: Any) -> bool:
    """Process a single reconciliation event for a transaction payload."""
    trans_id = str(event.get("TransID") or event.get("trans_id") or "unknown").strip()
    tenant_id = _trusted_message_tenant(event)

    try:
        seen_trans_ids: Set[str] = set()
        tenant_settings = settings_store.get(tenant_id)
        anomalies = check_for_anomalies(event, seen_trans_ids, tenant_settings)

        connector = connector_registry.get_connector(tenant_id)
        if connector and hasattr(connector, "fetch_candidate_records"):
            raw_amount = event.get("TransAmount")
            if raw_amount is None:
                raw_amount = event.get("amount")
            # Route the production reconciliation consumer through the shared
            # monetary contract. A bare ``Decimal(str(raw))`` accepted NaN,
            # Infinity and sub-minor-unit precision, which produced an unbounded
            # candidate tolerance (NaN * 0.005) and let a connector fetch a
            # mismatched candidate set. An invalid amount now yields no
            # amount-scoped fetch instead of an invalid query.
            currency = DEFAULT_CURRENCY
            try:
                currency_value = next(
                    (event[key] for key in ("Currency", "currency") if key in event),
                    DEFAULT_CURRENCY,
                )
                if currency_value is None or not str(currency_value).strip():
                    raise NormalizationError("currency must not be empty when supplied")
                currency = normalize_currency(currency_value)
                amount = (
                    Decimal(normalize_amount(raw_amount, currency=currency))
                    if raw_amount is not None
                    else None
                )
            except NormalizationError:
                amount = None
            tolerance = (
                max(currency_quantum(currency), amount * Decimal("0.005"))
                if amount is not None
                else None
            )
            internal_records = connector.fetch_candidate_records(
                since_minutes=WINDOW_MINUTES,
                amount=amount,
                phone_number=str(event.get("MSISDN") or event.get("phone_number") or "").strip() or None,
                tolerance=tolerance,
                limit=500,
                reference=str(
                    event.get("BillRefNumber")
                    or event.get("reference")
                    or ""
                ).strip() or None,
                currency=currency,
            )
        else:
            internal_records = connector.fetch_recent_records(since_minutes=WINDOW_MINUTES) if connector else []

        phase3_result = reconciliation_engine.reconcile(
            event,
            internal_records,
            seen_transaction_ids=seen_trans_ids,
        )
        phase3_status = phase3_result["status"]
        compatibility = {
            "MATCHED": ("matched", "info"),
            "UNMATCHED": ("missing_payment", "critical"),
            "PARTIAL": ("needs_review", "warning"),
            "MISMATCH": ("needs_review", "warning"),
            "DUPLICATE": ("duplicate_ignored", "info"),
            "PENDING": ("pending", "warning"),
            "EXCEPTION": ("needs_review", "critical"),
        }
        legacy_status, severity = compatibility[phase3_status]
        evaluation = {
            "trans_id": trans_id,
            "status": legacy_status,
            "severity": severity,
            "phase3_status": phase3_status,
            "match": {"match_type": phase3_status.lower(), "evidence": phase3_result["evidence"]},
            "evidence": phase3_result["evidence"],
            "processing_latency_ms": phase3_result["processing_latency_ms"],
            "anomalies": list(phase3_result["evidence"].get("matching_rules", [])) if phase3_status != "MATCHED" else [],
        }

        evaluation["tenant_id"] = tenant_id
        evaluation["event"] = event
        evaluation["checked_at"] = datetime.now(timezone.utc).isoformat()
        evaluation["anomalies"] = list(set(anomalies + evaluation.get("anomalies", [])))

        logger.info(
            "Reconciled trans_id=%s tenant_id=%s status=%s severity=%s",
            trans_id, tenant_id, evaluation["status"], evaluation["severity"]
        )

        persist_result = _persist_atomically(event, evaluation, trans_id, tenant_id)

        if persist_result == ProcessResult.DUPLICATE:
            logger.info("Duplicate trans_id=%s caught during flush, advancing offset.", trans_id)
            return True

        if persist_result in {ProcessResult.ERROR, ProcessResult.CONFLICT}:
            logger.error("Persistence failed for trans_id=%s. Offset NOT committed for retry.", trans_id)
            return False

        if producer is not None:
            _publish_downstream(evaluation, trans_id, producer, tenant_id)
        return True

    except Exception as exc:
        logger.exception("Unexpected error processing trans_id=%s in reconciliation loop: %s", trans_id, exc)
        return False


def _commit_message(consumer: Any, message: Any) -> None:
    """Commit exactly this message, including its partition and next offset."""
    if not hasattr(consumer, "commit"):
        raise RuntimeError("Kafka consumer does not support explicit offset commits")
    try:
        from kafka import TopicPartition
        from kafka.structs import OffsetAndMetadata
    except ImportError as exc:
        raise RuntimeError("Kafka offset commit support is unavailable") from exc
    consumer.commit({
        TopicPartition(message.topic, message.partition):
        OffsetAndMetadata(message.offset + 1, None)
    })


def _message_traceparent(message: Any) -> Optional[str]:
    """Extract a W3C traceparent header from a kafka-python message if the producer injected one."""
    headers = getattr(message, "headers", None) or []
    for key, value in headers:
        if str(key).lower() == "traceparent" and value:
            try:
                return value.decode("ascii")
            except Exception:
                continue
    return None


def _decode_message(value: Any) -> Dict[str, Any]:
    """Decode a Kafka value without letting malformed JSON escape the disposition path."""
    if isinstance(value, dict):
        event = value
    else:
        if isinstance(value, (bytes, bytearray, memoryview)):
            value = bytes(value).decode("utf-8")
        if not isinstance(value, str):
            raise ValueError("Kafka message must contain a JSON object")
        event = json.loads(value)
    if not isinstance(event, dict):
        raise ValueError("Kafka message must contain a JSON object")
    return event


def _handle_message(
    consumer: Any,
    message: Any,
    producer: Any,
    connector_registry: Any,
) -> bool:
    """Persist a processing or quarantine disposition before committing its offset.

    A successful return means the DB outcome is durable and Kafka acknowledged
    the next offset. False means the payload was durably quarantined and acked.
    Failures in either persistence or offset commit raise so the caller can pause
    and seek this partition for retry rather than advance past the message.
    """
    try:
        event = _decode_message(message.value)
    except (UnicodeDecodeError, json.JSONDecodeError, TypeError, ValueError):
        if not _dead_letter_message(message, reason="invalid_reconciliation_message"):
            raise _MessageRetryRequired("Could not durably quarantine malformed Kafka message")
        _commit_or_retry(consumer, message)
        return False

    try:
        tenant_id = _trusted_message_tenant(event)
        with tenant_scope(tenant_id):
            outcome = _process_message(event, consumer, producer, connector_registry)
    except Exception:
        logger.exception(
            "Reconciliation processing raised for topic=%s partition=%s offset=%s",
            getattr(message, "topic", None),
            getattr(message, "partition", None),
            getattr(message, "offset", None),
        )
        outcome = False

    if outcome is True:
        _commit_or_retry(consumer, message)
        return True

    if not _dead_letter_message(message):
        raise _MessageRetryRequired("Could not durably quarantine failed reconciliation message")
    _commit_or_retry(consumer, message)
    return False


def _commit_or_retry(consumer: Any, message: Any) -> None:
    try:
        _commit_message(consumer, message)
    except Exception as exc:
        logger.exception(
            "Kafka offset commit failed after durable disposition for topic=%s partition=%s offset=%s",
            getattr(message, "topic", None),
            getattr(message, "partition", None),
            getattr(message, "offset", None),
        )
        raise _MessageRetryRequired("Kafka offset commit must be retried") from exc


def _pause_partition_for_retry(consumer: Any, partition: Any, message: Any) -> None:
    """Keep later records in a blocked partition from being committed past this offset."""
    if partition is None:
        try:
            from kafka import TopicPartition
            partition = TopicPartition(message.topic, message.partition)
        except (ImportError, AttributeError) as exc:
            raise RuntimeError("Cannot safely identify failed Kafka partition") from exc
    pause = getattr(consumer, "pause", None)
    seek = getattr(consumer, "seek", None)
    if not callable(pause) or not callable(seek):
        raise RuntimeError("Kafka consumer must support pause and seek for safe message retry")

    try:
        pause(partition)
        seek(partition, message.offset)
    except Exception:
        logger.exception(
            "Could not pause and seek Kafka partition=%s to offset=%s; stopping consumption",
            partition,
            getattr(message, "offset", None),
        )
        raise
    _PAUSED_PARTITIONS[partition] = time.monotonic() + DEAD_LETTER_RETRY_SECONDS
    logger.error(
        "Paused Kafka partition=%s at offset=%s for %.1fs because durable message disposition failed",
        partition,
        message.offset,
        DEAD_LETTER_RETRY_SECONDS,
    )


def _resume_due_partitions(consumer: Any, now: Optional[float] = None) -> None:
    """Resume storage-blocked partitions after the bounded retry delay."""
    current_time = time.monotonic() if now is None else now
    for partition, resume_at in list(_PAUSED_PARTITIONS.items()):
        if resume_at > current_time:
            continue
        consumer.resume(partition)
        del _PAUSED_PARTITIONS[partition]
        logger.info("Resumed Kafka partition=%s for durable message retry", partition)


def process_polled_batch(message_batch: Dict[Any, Any], consumer: Any, producer: Any, connector_registry: Any) -> Dict[str, int]:
    """Process a bounded poll while preserving message order within each partition."""
    processed = 0
    failed = 0
    for partition, messages in message_batch.items():
        for message in list(messages)[:BATCH_SIZE]:
            if not _RUNNING:
                break
            traceparent = _message_traceparent(message)
            if traceparent:
                try:
                    from logging_utils import bind_observability_context
                    from observability import extract_trace_context
                    parsed = extract_trace_context({"traceparent": traceparent})
                    if parsed:
                        bind_observability_context(trace_id=parsed["trace_id"], span_id=parsed["span_id"], traceparent=traceparent)
                except Exception:
                    logger.debug("Could not bind incoming W3C trace context from message headers", exc_info=True)
            try:
                if _handle_message(consumer, message, producer, connector_registry):
                    processed += 1
                else:
                    failed += 1
            except _MessageRetryRequired:
                failed += 1
                _pause_partition_for_retry(consumer, partition, message)
                break
    return {"processed": processed, "failed": failed}


def _dead_letter_message(
    message: Any,
    reason: str = "reconciliation_message_processing_failed",
    *,
    tenant_id: str | None = None,
) -> bool:
    """Persist a replayable disposition and Kafka source coordinates for a failed message."""
    raw_value = getattr(message, "value", None)
    if isinstance(raw_value, dict):
        payload = raw_value
    elif isinstance(raw_value, (bytes, bytearray, memoryview)):
        payload = {"raw_message_base64": base64.b64encode(bytes(raw_value)).decode("ascii")}
    else:
        payload = {"raw_message": str(raw_value)}
    if tenant_id is None and isinstance(raw_value, dict):
        candidate_tenant_id = raw_value.get("tenant_id") or raw_value.get("TenantID")
        if isinstance(candidate_tenant_id, str) and candidate_tenant_id.strip():
            try:
                with tenant_scope(candidate_tenant_id.strip()):
                    tenant_id = candidate_tenant_id.strip()
            except ValueError:
                tenant_id = None
    tenant_id = tenant_id or UNATTRIBUTED_TENANT_ID
    source = {
        "topic": getattr(message, "topic", None),
        "partition": getattr(message, "partition", None),
        "offset": getattr(message, "offset", None),
        "disposition": "quarantined",
    }
    try:
        persisted = event_store.write_dead_letter(
            payload,
            reason=reason,
            error_detail=json.dumps(source, sort_keys=True, separators=(",", ":")),
            tenant_id=tenant_id,
        )
        if not persisted:
            logger.error(
                "Dead-letter store did not persist topic=%s partition=%s offset=%s",
                source["topic"],
                source["partition"],
                source["offset"],
            )
            return False
        return True
    except Exception:
        logger.exception(
            "Dead-letter persistence failed for topic=%s partition=%s offset=%s",
            source["topic"],
            source["partition"],
            source["offset"],
        )
        return False


def _close_worker_resources(consumer: Any, producer: Any) -> None:
    """Close each Kafka resource during shutdown so one failure cannot leak the other."""
    for name, resource, kwargs in (
        ("consumer", consumer, {}),
        ("producer", producer, {"timeout": 5}),
    ):
        close = getattr(resource, "close", None)
        if not callable(close):
            continue
        try:
            close(**kwargs)
        except Exception:
            logger.exception("Failed to close Kafka %s during reconciliation shutdown", name)


def run():
    """Main execution loop for the reconciliation Kafka consumer service."""
    if not HAS_KAFKA or KafkaConsumer is None or KafkaProducer is None:
        logger.error("Kafka client dependencies unavailable. Reconciliation job exiting.")
        sys.exit(1)

    logger.info("Initializing Reconciliation Job consumer on topic='%s'...", TOPIC_RAW)

    consumer = KafkaConsumer(
        TOPIC_RAW,
        bootstrap_servers=KAFKA_BOOTSTRAP_SERVERS,
        group_id="pesaguard-reconciliation-v2",
        auto_offset_reset="earliest",
        enable_auto_commit=False,
        max_poll_records=BATCH_SIZE,
    )

    producer = KafkaProducer(
        bootstrap_servers=KAFKA_BOOTSTRAP_SERVERS,
        value_serializer=lambda v: json.dumps(v, ensure_ascii=False).encode("utf-8"),
        retries=3,
        acks="all",
    )

    connector_registry = ConnectorRegistry.from_env()
    logger.info("Reconciliation worker active and listening for M-Pesa callbacks...")
    last_outbox_publish = 0.0
    _touch_heartbeat()

    while _RUNNING:
        _resume_due_partitions(consumer)
        _touch_heartbeat()
        now = time.monotonic()
        if now - last_outbox_publish >= 5:
            try:
                publish_reconciliation_outbox_batch(producer, limit=BATCH_SIZE)
            except Exception:
                logger.exception("Reconciliation outbox recovery pass failed; continuing Kafka consumption")
            last_outbox_publish = now
        if hasattr(consumer, "poll"):
            message_batch = consumer.poll(timeout_ms=1000)
            if not message_batch:
                continue
            process_polled_batch(message_batch, consumer, producer, connector_registry)
        else:
            for message in consumer:
                if not _RUNNING:
                    break
                try:
                    _handle_message(consumer, message, producer, connector_registry)
                except _MessageRetryRequired:
                    _pause_partition_for_retry(consumer, None, message)
                    break

    logger.info("Cleaning up Kafka consumer resources...")
    _close_worker_resources(consumer, producer)
    logger.info("Reconciliation Job stopped cleanly.")


if __name__ == "__main__":
    run()
