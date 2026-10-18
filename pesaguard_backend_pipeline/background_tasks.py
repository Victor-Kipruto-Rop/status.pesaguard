"""
Enterprise-grade background task queue and async worker management for PesaGuard.
Handles distributed job enqueueing, RQ failure hooks, dead-letter recording, and scheduled report generation.
"""

from __future__ import annotations

from environment import required_env

import logging
import os
import uuid
from datetime import datetime, timedelta, timezone
from typing import Any, Dict, List, Optional

from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from observability import capture_exception, init_sentry
from tenant_context import tenant_scope

logger = logging.getLogger("pesaguard.background_tasks")

init_sentry(service="background_worker", provider="redis_rq")

REDIS_URL = os.getenv("REDIS_URL", "redis://localhost:6379/0")
RQ_QUEUE_NAME = os.getenv("RQ_QUEUE_NAME", "transaction_events")
DATABASE_URL = required_env("DATABASE_URL")
OUTBOX_MAX_ATTEMPTS = max(1, int(os.getenv("OUTBOX_MAX_ATTEMPTS", "5")))
UNATTRIBUTED_TENANT_ID = os.getenv("PESAGUARD_UNATTRIBUTED_TENANT_ID", "pesaguard-unattributed")
DEAD_LETTER_REPLAY_LEASE_SECONDS = 900


class DeadLetterReplayRecoveryRequired(RuntimeError):
    """A stale replay needs an operator decision before another attempt."""

# Global thread-safe engine for task-level database persistence
if DATABASE_URL.startswith("sqlite"):
    _task_db_engine = create_engine(
        DATABASE_URL,
        connect_args={"check_same_thread": False},
    )
else:
    _task_db_engine = create_engine(
        DATABASE_URL,
        pool_pre_ping=True,
        pool_size=int(os.getenv("DB_POOL_SIZE", "5")),
        max_overflow=int(os.getenv("DB_MAX_OVERFLOW", "10")),
    )
TaskSessionLocal = sessionmaker(bind=_task_db_engine, expire_on_commit=False)

try:
    from metrics import instrument_engine_query_timing
    instrument_engine_query_timing(_task_db_engine)
except Exception:
    logger.debug("Task DB engine query timing instrumentation skipped.", exc_info=True)


def handle_job_failure(job, connection, type, value, traceback) -> None:
    """
    RQ Failure Handler Callback.
    Executes automatically when a background RQ job exhausts all retry attempts.
    Logs high-severity alerts and writes job details to the DeadLetter repository.
    """
    job_id = getattr(job, "id", "unknown")
    func_name = getattr(job, "func_name", "unknown")
    args = getattr(job, "args", [])

    logger.error(
        "CRITICAL: Async job failed permanently. Job ID: %s, Function: %s, Error: %s",
        job_id, func_name, value, exc_info=(type, value, traceback)
    )

    # Persist job failure into DeadLetter store if job payload is present
    try:
        from models import DeadLetter
        payload = args[1] if len(args) > 1 and isinstance(args[1], dict) else {"raw_args": str(args)}
        tenant_id = payload.get("tenant_id") if isinstance(payload, dict) else None
        if not isinstance(tenant_id, str) or not tenant_id.strip():
            tenant_id = UNATTRIBUTED_TENANT_ID
        with tenant_scope(tenant_id):
            session = TaskSessionLocal()
            try:
                dead_letter = DeadLetter(
                    id=f"dlq_job_{job_id}",
                    tenant_id=tenant_id,
                    reason="background_job_failed",
                    error_detail=f"Job {func_name} failed: {str(value)}",
                    payload=payload,
                    created_at=datetime.now(timezone.utc),
                )
                session.add(dead_letter)
                session.commit()
                logger.info("Successfully recorded job failure %s to DeadLetter table.", job_id)
            except Exception as exc:
                logger.exception("Failed writing job failure %s to DeadLetter table: %s", job_id, exc)
                session.rollback()
            finally:
                session.close()
    except Exception as exc:
        logger.error("Could not import DeadLetter model for failure handling: %s", exc)

    capture_exception(value, operation="background_job_failure", extra={
        "job_id": job_id,
        "func_name": func_name,
        "queue": RQ_QUEUE_NAME,
    })


def enqueue_transaction_event(topic: str, payload: dict) -> Dict[str, Any]:
    """
    Enqueue a transaction event into Redis/RQ for background Kafka publishing.
    Configures exponential retries and explicit failure callbacks.
    """
    if not isinstance(payload, dict):
        logger.error("Invalid transaction payload provided for enqueueing: %s", type(payload))
        return {"status": "failed", "error": "invalid_payload_type"}

    try:
        import redis
        import rq
    except ImportError as exc:
        logger.error("RQ or Redis dependencies missing in runtime environment: %s", exc)
        return {
            "status": "failed",
            "error": "rq or redis package not installed",
            "details": str(exc),
        }

    try:
        Queue = rq.Queue
    except (AttributeError, ImportError) as exc:
        logger.error("RQ dependency missing or incompatible in runtime environment: %s", exc)
        return {
            "status": "failed",
            "error": "rq or redis package not installed",
            "details": str(exc),
        }

    try:
        redis_conn = redis.from_url(REDIS_URL, socket_connect_timeout=5, socket_timeout=5)
        queue = Queue(name=RQ_QUEUE_NAME, connection=redis_conn)

        enqueue_kwargs = {
            "job_timeout": 30,
        }
        Retry = getattr(rq, "Retry", None)
        if Retry is not None:
            enqueue_kwargs["retry"] = Retry(max=3, interval=[10, 30, 60])
            enqueue_kwargs["on_failure"] = handle_job_failure

        job = queue.enqueue(
            _publish_transaction_event,
            topic,
            payload,
            **enqueue_kwargs,
        )
        
        trans_id = payload.get("TransID", "unknown")
        logger.info("Enqueued transaction event job_id=%s trans_id=%s to queue=%s", job.id, trans_id, RQ_QUEUE_NAME)
        
        return {
            "status": "queued",
            "job_id": job.id,
            "queue": RQ_QUEUE_NAME,
        }
    except Exception as exc:
        logger.exception("Failed to enqueue transaction event to Redis: %s", exc)
        return {"status": "failed", "error": str(exc)}


def enqueue_reconciliation_request(
    tenant_id: str,
    transaction_id: str,
    idempotency_key: str,
) -> Dict[str, Any]:
    """Queue a tenant-scoped reconciliation job with a stable RQ identity."""
    import hashlib

    try:
        import redis
        import rq
    except ImportError:
        logger.exception("RQ or Redis dependencies are unavailable for reconciliation")
        return {"status": "failed", "error": "queue_unavailable"}

    job_identity = hashlib.sha256(
        f"{tenant_id}\0{transaction_id}\0{idempotency_key}".encode("utf-8")
    ).hexdigest(    )
    job_id = f"reconciliation-{job_identity}"

    try:
        connection = redis.from_url(REDIS_URL, socket_connect_timeout=5, socket_timeout=5)
        queue = rq.Queue(name=RQ_QUEUE_NAME, connection=connection)
        existing = queue.fetch_job(job_id)
        if existing is not None:
            return {"status": "queued", "job_id": job_id, "queue": RQ_QUEUE_NAME}

        enqueue_kwargs = {"job_id": job_id, "job_timeout": 300}
        retry = getattr(rq, "Retry", None)
        if retry is not None:
            enqueue_kwargs["retry"] = retry(max=3, interval=[10, 30, 60])
            enqueue_kwargs["on_failure"] = handle_job_failure
        queue.enqueue(
            "api_core_jobs.process_reconciliation_request",
            tenant_id,
            transaction_id,
            **enqueue_kwargs,
        )
        return {"status": "queued", "job_id": job_id, "queue": RQ_QUEUE_NAME}
    except Exception:
        logger.exception(
            "Failed to enqueue reconciliation for tenant_id=%s transaction_id=%s",
            tenant_id,
            transaction_id,
        )
        return {"status": "failed", "error": "queue_unavailable"}


def enqueue_batch_import_drain() -> Dict[str, Any]:
    """Queue a bounded import drain for cron/systemd scheduled execution."""
    try:
        import redis
        import rq
        from batch_ingestion import process_queued_imports

        queue = rq.Queue(name=os.getenv("IMPORT_QUEUE_NAME", "batch_imports"), connection=redis.from_url(REDIS_URL))
        job = queue.enqueue(process_queued_imports, job_timeout=300)
        return {"status": "queued", "job_id": job.id}
    except Exception as exc:
        logger.exception("Failed to enqueue batch import drain: %s", exc)
        return {"status": "failed", "error": str(exc)}


def _publish_transaction_event(topic: str, payload: dict) -> None:
    """Worker job function that wraps synchronous Kafka publishing."""
    from producer import publish_transaction_event
    publish_transaction_event(topic, payload)


def replay_dead_letter_job(dead_letter_id: str, tenant_id: str) -> None:
    """Claim and replay one tenant dead-letter without concurrent redelivery."""
    from data_protection import unprotect_payload
    from models import DeadLetter
    from producer import publish_transaction_event

    session_factory = sessionmaker(bind=_task_db_engine, expire_on_commit=False)
    with tenant_scope(tenant_id):
        with session_factory() as session:
            entry = session.query(DeadLetter).filter(
                DeadLetter.id == dead_letter_id,
                DeadLetter.tenant_id == tenant_id,
            ).with_for_update().first()
            if entry is None:
                raise LookupError("tenant-scoped dead-letter entry was not found")
            if entry.replay_status in {"published", "ingested", "duplicate"}:
                return
            if entry.replay_status == "replaying":
                last_attempt = entry.replayed_at
                if last_attempt is None:
                    stale = True
                else:
                    if last_attempt.tzinfo is None:
                        last_attempt = last_attempt.replace(tzinfo=timezone.utc)
                    stale = (
                        datetime.now(timezone.utc) - last_attempt
                    ).total_seconds() >= DEAD_LETTER_REPLAY_LEASE_SECONDS
                if stale:
                    entry.replay_status = "failed"
                    entry.error_detail = (
                        "replay lease expired; operator review required before retry"
                    )
                    session.commit()
                    raise DeadLetterReplayRecoveryRequired(
                        "stale replay requires operator review"
                    )
                return
            if entry.replay_status != "queued":
                raise RuntimeError("dead-letter entry is not queued for replay")

            entry.replay_status = "replaying"
            entry.replayed_at = datetime.now(timezone.utc)
            session.commit()
            try:
                payload = unprotect_payload(entry.payload or {})
                if entry.reason == "validation_failed":
                    from event_store import EventStore, ProcessResult
                    from ingestion import IngestionError, IngestionService

                    result = IngestionService(EventStore(database_url=DATABASE_URL)).ingest(
                        "mpesa",
                        payload,
                        tenant_id=tenant_id,
                    )
                    if result.result in {ProcessResult.ERROR, ProcessResult.CONFLICT}:
                        raise IngestionError(
                            f"transaction replay was not accepted: {result.result.value}"
                        )
                    entry.replay_status = (
                        "duplicate" if result.result is ProcessResult.DUPLICATE else "ingested"
                    )
                else:
                    publish_transaction_event(
                        os.getenv("KAFKA_TOPIC_TRANSACTIONS", "pesaguard.transactions.raw"),
                        payload,
                    )
                    entry.replay_status = "published"
                entry.processed = True
                entry.processed_at = datetime.now(timezone.utc)
                session.commit()
            except Exception as exc:
                entry.replay_status = "failed"
                entry.error_detail = str(exc)[:2000]
                session.commit()
                raise


def drain_transaction_outbox(limit: int = 100) -> Dict[str, Any]:
    """Publish a bounded durable outbox batch and retain failures for replay."""
    from event_store import EventStore
    from producer import publish_transaction_batch_results
    from data_protection import unprotect_payload

    store = EventStore(database_url=DATABASE_URL)
    store._ensure_ready()
    claimed = []
    remaining = max(1, limit)
    tenant_ids = store.list_active_tenants() if store.engine.dialect.name == "postgresql" else [None]
    for tenant_id in tenant_ids:
        kwargs = {"tenant_id": tenant_id} if tenant_id is not None else {}
        tenant_rows = store.claim_outbox_batch(limit=remaining, **kwargs)
        claimed.extend(tenant_rows)
        remaining -= len(tenant_rows)
        if remaining <= 0:
            break
    published = 0
    failed = 0

    rows_by_topic: Dict[str, List[tuple[dict, dict]]] = {}
    for row in claimed:
        try:
            payload = unprotect_payload(row["payload"] or {})
            rows_by_topic.setdefault(row["topic"], []).append((row, payload))
        except Exception as exc:
            failed += 1
            _record_outbox_failure(store, row, exc)

    for topic, topic_rows in rows_by_topic.items():
        try:
            outcomes = publish_transaction_batch_results(topic, [payload for _, payload in topic_rows])
        except Exception as exc:
            logger.exception("Transaction outbox batch publish failed for topic=%s", topic)
            outcomes = [False] * len(topic_rows)
            failure_reason = exc
        else:
            failure_reason = "batch delivery failed"
        if len(outcomes) != len(topic_rows):
            logger.error(
                "Transaction outbox publisher returned an invalid result count for topic=%s: expected=%s actual=%s",
                topic,
                len(topic_rows),
                len(outcomes),
            )
            outcomes = list(outcomes[:len(topic_rows)])
            outcomes.extend([False] * (len(topic_rows) - len(outcomes)))
        for (row, _), delivered in zip(topic_rows, outcomes):
            if delivered:
                store.mark_outbox_published(row["id"], tenant_id=row["tenant_id"])
                published += 1
            else:
                failed += 1
                _record_outbox_failure(store, row, failure_reason)

    return {"status": "ok" if failed == 0 else "partial_failure", "claimed": len(claimed), "published": published, "failed": failed}


def _record_outbox_failure(store: Any, row: dict, error: Any) -> None:
    if row["attempts"] >= OUTBOX_MAX_ATTEMPTS:
        store.mark_outbox_dead_lettered(row["id"], str(error), tenant_id=row["tenant_id"])
        from metrics import record_pipeline_event
        record_pipeline_event("dead_lettered", tenant_id=row.get("tenant_id", "default"))
        logger.error("Transaction outbox row moved to dead letter after %s attempts: row=%s", row["attempts"], row["id"])
    else:
        retry_seconds = min(30 * (2 ** max(row["attempts"] - 1, 0)), 3600)
        store.mark_outbox_failed(
            row["id"],
            str(error),
            retry_seconds=retry_seconds,
            tenant_id=row["tenant_id"],
        )
        from metrics import record_event_retry, record_pipeline_event
        record_event_retry()
        record_pipeline_event("retried", tenant_id=row.get("tenant_id", "default"))
    logger.error("Transaction outbox publish failed for row=%s: %s", row["id"], error)


def enqueue_transaction_outbox_drain() -> Dict[str, Any]:
    """Schedule durable outbox delivery without putting payload durability in Redis."""
    try:
        import redis
        import rq

        queue = rq.Queue(name=RQ_QUEUE_NAME, connection=redis.from_url(REDIS_URL, socket_connect_timeout=5, socket_timeout=5))
        job = queue.enqueue(drain_transaction_outbox, job_timeout=60)
        return {"status": "queued", "job_id": job.id, "queue": RQ_QUEUE_NAME}
    except Exception as exc:
        logger.warning("Unable to schedule transaction outbox drain: %s", exc)
        return {"status": "deferred", "error": str(exc)}


def enqueue_notification_event(event: dict) -> Dict[str, Any]:
    """Queue a notification command without coupling business workers to providers."""
    if not isinstance(event, dict) or not event.get("event") or not event.get("tenant_id"):
        return {"status": "failed", "error": "invalid_notification_event"}
    from communications.events import SUPPORTED_NOTIFICATION_EVENTS

    if event.get("event") not in SUPPORTED_NOTIFICATION_EVENTS:
        return {"status": "failed", "error": "unsupported_notification_event"}
    event = dict(event)
    event_id = event.get("event_id")
    if event_id is None:
        event_id = uuid.uuid4().hex
    if not isinstance(event_id, str) or not event_id.strip() or len(event_id) > 255:
        return {"status": "failed", "error": "invalid_notification_event_id"}
    event["event_id"] = event_id.strip()
    try:
        import redis
        import rq

        queue = rq.Queue(name=os.getenv("NOTIFICATION_RQ_QUEUE_NAME", "notification_events"), connection=redis.from_url(REDIS_URL))
        job = queue.enqueue(
            _process_notification_event,
            event,
            job_timeout=30,
            retry=rq.Retry(max=3, interval=[10, 30, 60]),
            on_failure=handle_job_failure,
        )
        return {"status": "queued", "job_id": job.id, "queue": queue.name}
    except Exception as exc:
        logger.exception("Failed to enqueue notification event: %s", exc)
        return {"status": "failed", "error": "notification_queue_unavailable"}


def _notification_alert_context(event: dict) -> dict:
    """Normalize a typed notification event into an alert-center record."""
    from communications.events import SUPPORTED_NOTIFICATION_EVENTS

    event_name = event.get("event")
    if event_name not in SUPPORTED_NOTIFICATION_EVENTS:
        raise ValueError("unsupported notification event")
    context = dict(event.get("context") or {})
    context.setdefault("event", event_name)
    context["alert_type"] = context.get("alert_type") or event_name.replace(".", "_")
    context["source"] = context.get("source") or event_name.split(".", 1)[0]
    context["title"] = context.get("title") or event_name.replace(".", " ").replace("_", " ").title()
    if not context.get("message"):
        context["message"] = context.get("details") or context["title"]
    if not any(context.get(key) for key in (
        "deduplication_key", "fingerprint", "id", "trans_id", "transaction_id",
    )):
        context["deduplication_key"] = event.get("event_id")

    default_severity = {
        "fraud.confirmed": "critical",
        "fraud.blocked": "critical",
        "security.mfa_required": "critical",
        "provider.failed": "critical",
        "fraud.suspected": "warning",
        "transaction.failed": "warning",
        "transaction.reversed": "warning",
        "transaction.duplicate": "warning",
        "transaction.mismatch": "warning",
        "reconciliation.exception_created": "warning",
        "provider.degraded": "warning",
    }.get(event_name, "info")
    context["severity"] = context.get("severity") or default_severity
    context["tenant_id"] = str(event["tenant_id"])
    context["event_id"] = str(event["event_id"])
    context.setdefault("request_id", event.get("correlation_id"))
    return context


def _process_notification_event(event: dict) -> None:
    """Persist an event-triggered alert and queue configured channels from a worker session."""
    from alerting_service import AlertingService
    from event_store import EventStore
    from tenant_settings import TenantSettingsStore

    tenant_id = str(event["tenant_id"])
    context = _notification_alert_context(event)
    store = EventStore()
    store._ensure_ready()
    session = store.Session()
    try:
        result = AlertingService(
            session=session,
            tenant_settings=TenantSettingsStore().get(tenant_id),
        ).handle_discrepancy(context)
        if result.get("status") == "failed":
            raise RuntimeError("one or more alert channels failed; retrying the durable event")
        if any(item.get("status") == "processing" for item in result.get("deliveries", [])):
            raise RuntimeError("alert channel delivery is still processing; retrying the durable event")
    except Exception:
        session.rollback()
        raise
    finally:
        session.close()


def _list_tenant_ids(store: Any) -> List[str]:
    """Safely discover all active tenant IDs from the TenantSettingsStore."""
    if hasattr(store, "list_tenant_ids") and callable(store.list_tenant_ids):
        return list(store.list_tenant_ids())
    
    if hasattr(store, "get_all_tenants") and callable(store.get_all_tenants):
        return list(store.get_all_tenants().keys())

    if hasattr(store, "_data") and isinstance(store._data, dict):
        logger.warning("TenantSettingsStore lacks list_tenant_ids(); falling back to internal _data structure.")
        return list(store._data.keys())

    logger.warning("Unable to dynamically inspect tenants from store. Defaulting to ['default'].")
    return ["default"]


def generate_reports(report_type: str = "daily", tenant_id: Optional[str] = None) -> Dict[str, Any]:
    """
    Generate automated reconciliation summary reports per tenant and persist them.
    Executes with dedicated database sessions per tenant to guarantee transaction isolation.
    
    Args:
        report_type: "daily" or "weekly"
        tenant_id: Optional single-tenant filter override.
    """
    try:
        from models import Discrepancy, Report
        from tenant_settings import TenantSettingsStore
    except Exception as exc:
        logger.error("Failed to load required dependencies for report generation: %s", exc)
        return {"status": "failed", "error": str(exc)}

    now = datetime.now(timezone.utc)
    if report_type == "daily":
        period_end = now.replace(hour=0, minute=0, second=0, microsecond=0)
        period_start = period_end - timedelta(days=1)
    elif report_type == "weekly":
        period_end = now.replace(hour=0, minute=0, second=0, microsecond=0)
        period_start = period_end - timedelta(days=7)
    else:
        return {"status": "failed", "error": f"invalid_report_type: {report_type}"}

    store = TenantSettingsStore()
    if tenant_id:
        tenants = [tenant_id]
    else:
        try:
            tenants = _list_tenant_ids(store)
        except Exception as exc:
            logger.exception("Failed determining tenant list for report generation: %s", exc)
            return {"status": "failed", "error": "could_not_determine_tenant_list"}

    created_count = 0
    failed_tenants: List[str] = []

    # Execute isolated per-tenant report generation transactions
    for tenant in tenants:
        session = TaskSessionLocal()
        try:
            discrepancy_count = (
                session.query(Discrepancy)
                .filter(Discrepancy.tenant_id == tenant)
                .filter(Discrepancy.detected_at >= period_start)
                .filter(Discrepancy.detected_at < period_end)
                .count()
            )

            report = Report(
                id=f"rpt_{int(datetime.now(timezone.utc).timestamp())}_{uuid.uuid4().hex[:6]}",
                tenant_id=tenant,
                report_type=report_type,
                period_start=period_start,
                period_end=period_end,
                content={
                    "discrepancy_count": discrepancy_count,
                    "generated_at": now.isoformat(),
                    "period_days": 1 if report_type == "daily" else 7,
                },
                status="generated",
                created_at=now,
            )
            session.add(report)
            session.commit()
            created_count += 1
            logger.info("Successfully generated %s report for tenant_id=%s", report_type, tenant)

        except Exception as exc:
            logger.exception("Failed generating %s report for tenant_id=%s: %s", report_type, tenant, exc)
            session.rollback()
            failed_tenants.append(tenant)
        finally:
            session.close()

    return {
        "status": "ok" if not failed_tenants else "partial_failure",
        "report_type": report_type,
        "created_reports": created_count,
        "failed_tenants": failed_tenants,
        "period_start": period_start.isoformat(),
        "period_end": period_end.isoformat(),
    }
