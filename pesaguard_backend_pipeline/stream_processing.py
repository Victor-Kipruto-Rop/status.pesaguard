"""
Stream Processing for PesaGuard real-time transaction pipeline.

Production-grade stream processing:
1. Real-time transaction enrichment
2. Streaming reconciliation where business rules permit
3. Event-time window aggregation
4. Stateful duplicate detection with TTL windows
5. Continuous anomaly indicators
6. Late-event corrections
7. Checkpointing and recovery
8. Replay-safe processing
9. Stream-versus-batch consistency checks
"""

from __future__ import annotations

import hashlib
import json
import logging
from collections import OrderedDict
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from decimal import Decimal
from typing import Any, Callable, Dict, Iterable, Mapping, Optional, Sequence, Set

from enrichment_service import EnrichmentService
from event_bus import EventEnvelope
from fraud_risk_engine import assess_transaction
from ingestion import CanonicalTransaction
from reconciliation_engine import ReconciliationEngine
from normalization import normalize_amount, normalize_currency

logger = logging.getLogger("pesaguard.stream_processing")


@dataclass(frozen=True)
class EnrichedTransaction:
    event_id: str
    tenant_id: str
    aggregate_id: str
    correlation_id: str
    occurred_at: str
    canonical: CanonicalTransaction
    enrichment: Dict[str, Any]
    risk_decision: Optional[Dict[str, Any]]
    stage: str = "enriched"
    causation_id: Optional[str] = None


def enrich_stream_event(
    event: EventEnvelope,
    *,
    enrichment_service: EnrichmentService,
    fraud_risk_enabled: bool = True,
) -> EnrichedTransaction:
    canonical = _event_to_canonical(event)
    context = enrichment_service.enrich(canonical)
    risk_decision = None
    if fraud_risk_enabled:
        decision = assess_transaction(
            {
                "TransID": canonical.transaction_id,
                "TransAmount": canonical.amount,
                "Currency": canonical.currency,
                "TransTime": canonical.transaction_time,
                "MSISDN": canonical.phone_number,
                "TransactionType": canonical.transaction_type,
                "Status": canonical.status,
            },
            history=[],
        )
        risk_decision = decision.as_dict()
    return EnrichedTransaction(
        event_id=event.event_id,
        tenant_id=event.tenant_id,
        aggregate_id=event.aggregate_id,
        correlation_id=event.correlation_id,
        occurred_at=event.occurred_at,
        canonical=canonical,
        enrichment=enrichment_service.to_payload(context),
        risk_decision=risk_decision,
        causation_id=event.event_id,
    )


def _event_to_canonical(event: EventEnvelope) -> CanonicalTransaction:
    payload = event.payload
    currency = normalize_currency(
        payload.get("Currency") or payload.get("currency") or "KES"
    )
    amount = normalize_amount(
        payload.get("TransAmount", payload.get("amount")), currency=currency
    )
    return CanonicalTransaction(
        tenant_id=event.tenant_id,
        provider=str(payload.get("provider") or "unknown"),
        provider_account_id=str(
            payload.get("provider_account_id") or payload.get("BusinessShortCode") or "unknown"
        ),
        transaction_id=str(
            payload.get("transaction_id")
            or payload.get("TransID")
            or event.aggregate_id
        ),
        provider_transaction_id=str(
            payload.get("provider_transaction_id") or payload.get("TransID") or event.aggregate_id
        ),
        account_id=str(payload.get("account_id") or payload.get("provider_account_id") or "unknown"),
        amount=amount,
        currency=currency,
        transaction_type=str(
            payload.get("transaction_type") or payload.get("TransactionType") or "PAYMENT"
        ),
        status=str(payload.get("status") or payload.get("Status") or "RECEIVED"),
        transaction_time=str(
            payload.get("transaction_time") or payload.get("TransTime") or event.occurred_at
        ),
        phone_number=str(payload.get("phone_number") or payload.get("MSISDN") or "unknown"),
        merchant_id=str(payload.get("merchant_id") or ""),
        source=str(payload.get("source") or event.source),
        metadata=dict(payload.get("metadata") or {}),
        created_at=str(payload.get("created_at") or event.occurred_at),
        updated_at=str(payload.get("updated_at") or event.occurred_at),
    )


@dataclass(frozen=True)
class StreamingReconciliationResult:
    transaction_id: str
    tenant_id: str
    status: str
    matched_records: list[str]
    anomalies: list[str]
    latency_ms: float
    event_time: str
    processed_at: str
    reconciliation_id: str


class StreamingReconciliation:
    def __init__(
        self,
        *,
        window_seconds: int = 900,
        tolerance_percent: Decimal = Decimal("0.5"),
        max_window_records: int = 50_000,
    ):
        self.engine = ReconciliationEngine(
            tolerance_percent=tolerance_percent, window_seconds=window_seconds
        )
        self.window_seconds = window_seconds
        self.max_window_records = max_window_records
        self._windows: Dict[str, OrderedDict[str, Dict[str, Any]]] = OrderedDict()
        self._completed: Dict[str, OrderedDict[str, StreamingReconciliationResult]] = (
            OrderedDict()
        )
        self._max_completed = 100_000

    def _evict_stale(self, tenant_id: str, now: datetime) -> None:
        cutoff = now - timedelta(seconds=self.window_seconds)
        window = self._windows.get(tenant_id)
        if window is None:
            return
        stale_keys = [
            k
            for k, v in window.items()
            if _parse_event_time(v.get("transaction_time") or v.get("timestamp")) < cutoff
        ]
        for k in stale_keys:
            del window[k]

    def _evict_stale_completed(self, tenant_id: str, now: datetime) -> None:
        completed = self._completed.get(tenant_id)
        if completed is None:
            return
        cutoff = now - timedelta(seconds=self.window_seconds)
        stale_keys = [
            k for k, v in completed.items() if _parse_event_time(v.event_time) < cutoff
        ]
        for k in stale_keys:
            del completed[k]

    def reconcile_stream_event(
        self,
        event: EventEnvelope,
        *,
        internal_records: Sequence[Dict[str, Any]],
        now: Optional[datetime] = None,
    ) -> StreamingReconciliationResult:
        now = now or datetime.now(timezone.utc)
        if now.tzinfo is None:
            now = now.replace(tzinfo=timezone.utc)
        tenant_id = event.tenant_id
        event_id = event.event_id
        completed = self._completed.setdefault(tenant_id, OrderedDict())
        if event_id in completed:
            return completed[event_id]
        self._evict_stale(tenant_id, now)
        self._evict_stale_completed(tenant_id, now)
        window = self._windows.setdefault(tenant_id, OrderedDict())
        for rec in internal_records:
            rec_tenant = rec.get("tenant_id") or tenant_id
            if rec_tenant != tenant_id:
                continue
            rec_id = str(rec.get("transaction_id") or rec.get("trans_id") or rec.get("id"))
            if rec_id:
                window[rec_id] = rec
        while len(window) > self.max_window_records:
            window.popitem(last=False)
        payload = event.payload
        transaction = {
            "transaction_id": str(payload.get("TransID") or event.aggregate_id),
            "TransID": str(payload.get("TransID") or event.aggregate_id),
            "tenant_id": tenant_id,
            "provider": str(payload.get("provider") or "unknown"),
            "amount": str(payload.get("TransAmount") or payload.get("amount") or "0"),
            "currency": str(payload.get("Currency") or payload.get("currency") or "KES"),
            "transaction_time": str(
                payload.get("TransTime") or payload.get("transaction_time") or event.occurred_at
            ),
            "status": str(payload.get("Status") or payload.get("status") or "RECEIVED"),
            "timestamp": str(
                payload.get("TransTime")
                or payload.get("transaction_time")
                or event.occurred_at
            ),
        }
        engine_result = self.engine.reconcile(
            transaction, list(window.values()), now=now
        )
        result = StreamingReconciliationResult(
            transaction_id=transaction["transaction_id"],
            tenant_id=tenant_id,
            status=engine_result.get("status", "EXCEPTION"),
            matched_records=list(engine_result.get("matched_records", [])),
            anomalies=list(engine_result.get("anomalies", [])),
            latency_ms=float(engine_result.get("latency_ms", 0.0)),
            event_time=transaction["transaction_time"],
            processed_at=now.isoformat(),
            reconciliation_id=engine_result.get("reconciliation_id", event_id),
        )
        completed[event_id] = result
        while len(completed) > self._max_completed:
            completed.popitem(last=False)
        return result


def _parse_event_time(value: Any) -> datetime:
    if not value:
        return datetime.min.replace(tzinfo=timezone.utc)
    if isinstance(value, datetime):
        return value if value.tzinfo else value.replace(tzinfo=timezone.utc)
    try:
        parsed = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
        return parsed if parsed.tzinfo else parsed.replace(tzinfo=timezone.utc)
    except ValueError:
        return datetime.min.replace(tzinfo=timezone.utc)



@dataclass(frozen=True)
class WindowBucket:
    window_start: str
    window_end: str
    event_count: int = 0
    total_amount: str = "0.00"
    currency: str = "KES"
    matched_count: int = 0
    unmatched_count: int = 0
    exception_count: int = 0
    risk_scores: list[float] = field(default_factory=list)


class WindowAggregator:
    def __init__(self, window_seconds: int = 300):
        self.window_seconds = window_seconds
        self._buckets: Dict[tuple[str, str], WindowBucket] = {}

    def _window_key(self, occurred_at: str) -> tuple[str, str]:
        dt = _parse_event_time(occurred_at)
        epoch = int(dt.timestamp())
        window_epoch = (epoch // self.window_seconds) * self.window_seconds
        start = datetime.fromtimestamp(window_epoch, tz=timezone.utc)
        end = start + timedelta(seconds=self.window_seconds)
        return start.isoformat(), end.isoformat()

    def add_event(
        self,
        tenant_id: str,
        occurred_at: str,
        amount: Optional[str] = None,
        currency: str = "KES",
        status: str = "received",
        risk_score: Optional[float] = None,
    ) -> None:
        window_start, window_end = self._window_key(occurred_at)
        key = (tenant_id, window_start)
        bucket = self._buckets.get(key)
        if bucket is None:
            bucket = WindowBucket(
                window_start=window_start,
                window_end=window_end,
                currency=currency,
            )
            self._buckets[key] = bucket
        # Build new bucket incrementally (frozen dataclass)
        event_count = bucket.event_count + 1
        total_amount = bucket.total_amount
        if amount is not None:
            try:
                current = Decimal(bucket.total_amount or "0")
                current += Decimal(amount)
                total_amount = str(current)
            except (ValueError, TypeError):
                pass
        matched_count = bucket.matched_count
        unmatched_count = bucket.unmatched_count
        exception_count = bucket.exception_count
        if status == "matched":
            matched_count += 1
        elif status in ("unmatched", "exception"):
            if status == "unmatched":
                unmatched_count += 1
            else:
                exception_count += 1
        risk_scores = list(bucket.risk_scores)
        if risk_score is not None:
            risk_scores.append(risk_score)
        bucket = WindowBucket(
            window_start=bucket.window_start,
            window_end=bucket.window_end,
            event_count=event_count,
            total_amount=total_amount,
            currency=bucket.currency,
            matched_count=matched_count,
            unmatched_count=unmatched_count,
            exception_count=exception_count,
            risk_scores=risk_scores,
        )
        self._buckets[key] = bucket

    def complete_windows_before(self, watermark: str) -> list[WindowBucket]:
        wm = _parse_event_time(watermark)
        completed: list[WindowBucket] = []
        to_delete: list[tuple[str, str]] = []
        for (tenant_id, window_start), bucket in self._buckets.items():
            end = _parse_event_time(bucket.window_end)
            if end <= wm:
                completed.append(bucket)
                to_delete.append((tenant_id, window_start))
        for key in to_delete:
            del self._buckets[key]
        return completed

    def snapshot(self) -> Dict[str, Any]:
        return {
            "active_windows": len(self._buckets),
            "windows": [
                {
                    "tenant_id": k[0],
                    "window_start": v.window_start,
                    "window_end": v.window_end,
                    "event_count": v.event_count,
                    "total_amount": v.total_amount,
                    "currency": v.currency,
                    "matched_count": v.matched_count,
                    "unmatched_count": v.unmatched_count,
                    "exception_count": v.exception_count,
                    "avg_risk_score": (
                        sum(v.risk_scores) / len(v.risk_scores) if v.risk_scores else 0.0
                    ),
                }
                for k, v in self._buckets.items()
            ],
        }



class DuplicateDetector:
    def __init__(self, ttl_seconds: int = 3600, max_entries: int = 1_000_000):
        self.ttl_seconds = ttl_seconds
        self.max_entries = max_entries
        self._seen: Dict[str, OrderedDict[str, tuple[str, datetime]]] = OrderedDict()

    def _evict_stale(self, tenant_id: str, now: datetime) -> None:
        seen = self._seen.get(tenant_id)
        if seen is None:
            return
        cutoff = now - timedelta(seconds=self.ttl_seconds)
        stale = [k for k, (_, seen_at) in seen.items() if seen_at < cutoff]
        for k in stale:
            del seen[k]

    def check_and_record(
        self,
        tenant_id: str,
        event_id: str,
        fingerprint: str,
        *,
        now: Optional[datetime] = None,
    ) -> tuple[bool, Optional[str]]:
        now = now or datetime.now(timezone.utc)
        if now.tzinfo is None:
            now = now.replace(tzinfo=timezone.utc)
        self._evict_stale(tenant_id, now)
        seen = self._seen.setdefault(tenant_id, OrderedDict())
        if fingerprint in seen:
            original_id, _ = seen[fingerprint]
            return True, original_id
        total = sum(len(s) for s in self._seen.values())
        while total >= self.max_entries:
            for t_key in self._seen:
                if self._seen[t_key]:
                    self._seen[t_key].popitem(last=False)
                    total -= 1
                    break
        seen[fingerprint] = (event_id, now)
        return False, None


def compute_event_fingerprint(event: EventEnvelope) -> str:
    payload = json.dumps(event.payload, sort_keys=True, default=str)
    encoded = f"{event.event_type}:{event.tenant_id}:{event.aggregate_id}:{payload}"
    return hashlib.sha256(encoded.encode("utf-8")).hexdigest()



@dataclass(frozen=True)
class AnomalyIndicator:
    tenant_id: str
    indicator_id: str
    indicator_type: str
    current_value: float
    threshold: float
    breach: bool
    window_start: str
    window_end: str
    event_count: int
    updated_at: str


class ContinuousAnomalyMonitor:
    def __init__(
        self,
        *,
        window_seconds: int = 300,
        velocity_threshold: float = 100.0,
        amount_deviation_threshold: float = 3.0,
        risk_score_threshold: float = 0.8,
    ):
        self.window_seconds = window_seconds
        self.velocity_threshold = velocity_threshold
        self.amount_deviation_threshold = amount_deviation_threshold
        self.risk_score_threshold = risk_score_threshold
        self._events: Dict[str, list[tuple[str, Optional[float], Optional[float]]]] = {}

    def observe(
        self,
        tenant_id: str,
        occurred_at: str,
        amount: Optional[str] = None,
        risk_score: Optional[float] = None,
        *,
        now: Optional[datetime] = None,
    ) -> list[AnomalyIndicator]:
        now = now or datetime.now(timezone.utc)
        if now.tzinfo is None:
            now = now.replace(tzinfo=timezone.utc)
        events = self._events.setdefault(tenant_id, [])
        amt = None
        if amount is not None:
            try:
                amt = Decimal(amount)
            except (ValueError, TypeError):
                amt = None
        events.append((occurred_at, amt, risk_score))
        cutoff = now - timedelta(seconds=self.window_seconds)
        cutoff_str = cutoff.isoformat()
        self._events[tenant_id] = [
            e for e in events if _parse_event_time(e[0]) >= cutoff
        ]
        window_events = self._events[tenant_id]
        indicators: list[AnomalyIndicator] = []
        window_start = (now - timedelta(seconds=self.window_seconds)).isoformat()
        window_end = now.isoformat()
        event_count = len(window_events)
        velocity = event_count / (self.window_seconds / 3600.0)
        if velocity > self.velocity_threshold:
            indicators.append(
                AnomalyIndicator(
                    tenant_id=tenant_id,
                    indicator_id=f"velocity:{tenant_id}:{window_start}",
                    indicator_type="velocity",
                    current_value=round(velocity, 2),
                    threshold=self.velocity_threshold,
                    breach=True,
                    window_start=window_start,
                    window_end=window_end,
                    event_count=event_count,
                    updated_at=now.isoformat(),
                )
            )
        amounts = [float(e[1]) for e in window_events if e[1] is not None]
        if len(amounts) >= 3:
            mean_amt = sum(amounts) / len(amounts)
            variance = sum((a - mean_amt) ** 2 for a in amounts) / len(amounts)
            std = variance**0.5
            latest = amounts[-1]
            if std > 0:
                z_score = abs((latest - mean_amt) / std)
                if z_score > self.amount_deviation_threshold:
                    indicators.append(
                        AnomalyIndicator(
                            tenant_id=tenant_id,
                            indicator_id=f"amount_deviation:{tenant_id}:{window_start}",
                            indicator_type="amount_deviation",
                            current_value=round(z_score, 2),
                            threshold=self.amount_deviation_threshold,
                            breach=True,
                            window_start=window_start,
                            window_end=window_end,
                            event_count=len(amounts),
                            updated_at=now.isoformat(),
                        )
                    )
        risk_scores = [e[2] for e in window_events if e[2] is not None]
        if risk_scores:
            avg_risk = sum(risk_scores) / len(risk_scores)
            if avg_risk > self.risk_score_threshold:
                indicators.append(
                    AnomalyIndicator(
                        tenant_id=tenant_id,
                        indicator_id=f"risk_score:{tenant_id}:{window_start}",
                        indicator_type="risk_score",
                        current_value=round(avg_risk, 4),
                        threshold=self.risk_score_threshold,
                        breach=True,
                        window_start=window_start,
                        window_end=window_end,
                        event_count=len(risk_scores),
                        updated_at=now.isoformat(),
                    )
                )
        return indicators



@dataclass(frozen=True)
class LateEventCorrection:
    original_event_id: str
    corrected_event_id: str
    tenant_id: str
    correction_type: str
    original_values: Dict[str, Any]
    corrected_values: Dict[str, Any]
    corrected_at: str
    reason: str


class LateEventCorrector:
    def __init__(self, max_corrections: int = 10_000):
        self.max_corrections = max_corrections
        self._corrections: Dict[str, Dict[str, LateEventCorrection]] = {}
        self._correction_log: list[LateEventCorrection] = []

    def register_correction(
        self,
        *,
        tenant_id: str,
        aggregate_id: str,
        original_event_id: str,
        corrected_event_id: str,
        correction_type: str,
        original_values: Mapping[str, Any],
        corrected_values: Mapping[str, Any],
        reason: str,
        now: Optional[datetime] = None,
    ) -> LateEventCorrection:
        now = now or datetime.now(timezone.utc)
        if now.tzinfo is None:
            now = now.replace(tzinfo=timezone.utc)
        correction = LateEventCorrection(
            original_event_id=original_event_id,
            corrected_event_id=corrected_event_id,
            tenant_id=tenant_id,
            correction_type=correction_type,
            original_values=dict(original_values),
            corrected_values=dict(corrected_values),
            corrected_at=now.isoformat(),
            reason=reason,
        )
        by_aggregate = self._corrections.setdefault(aggregate_id, OrderedDict())
        by_aggregate[original_event_id] = correction
        self._correction_log.append(correction)
        while len(self._correction_log) > self.max_corrections:
            self._correction_log.pop(0)
        return correction

    def get_corrections_for_aggregate(
        self, aggregate_id: str
    ) -> list[LateEventCorrection]:
        return list(self._corrections.get(aggregate_id, {}).values())

    def is_corrected(self, event_id: str) -> bool:
        for by_aggregate in self._corrections.values():
            if event_id in by_aggregate:
                return True
        return False



@dataclass(frozen=True)
class StreamCheckpoint:
    tenant_id: str
    stage: str
    last_event_id: str
    last_event_time: str
    offset: int
    state_hash: str
    checkpointed_at: str


class CheckpointStore:
    def __init__(
        self,
        *,
        persist_callback: Optional[Callable[[StreamCheckpoint], None]] = None,
    ):
        self.persist_callback = persist_callback
        self._checkpoints: Dict[tuple[str, str], StreamCheckpoint] = {}

    def save(
        self,
        *,
        tenant_id: str,
        stage: str,
        last_event_id: str,
        last_event_time: str,
        offset: int,
        state_hash: str,
    ) -> StreamCheckpoint:
        checkpoint = StreamCheckpoint(
            tenant_id=tenant_id,
            stage=stage,
            last_event_id=last_event_id,
            last_event_time=last_event_time,
            offset=offset,
            state_hash=state_hash,
            checkpointed_at=datetime.now(timezone.utc).isoformat(),
        )
        self._checkpoints[(tenant_id, stage)] = checkpoint
        if self.persist_callback is not None:
            self.persist_callback(checkpoint)
        return checkpoint

    def load(self, tenant_id: str, stage: str) -> Optional[StreamCheckpoint]:
        return self._checkpoints.get((tenant_id, stage))

    def all_checkpoints(self) -> list[StreamCheckpoint]:
        return list(self._checkpoints.values())


def compute_state_hash(state: Mapping[str, Any]) -> str:
    encoded = json.dumps(state, sort_keys=True, default=str)
    return hashlib.sha256(encoded.encode("utf-8")).hexdigest()



class ReplaySafeProcessor:
    def __init__(
        self,
        *,
        enrichment_service: EnrichmentService,
        checkpoint_store: Optional[CheckpointStore] = None,
        duplicate_detector: Optional[DuplicateDetector] = None,
        anomaly_monitor: Optional[ContinuousAnomalyMonitor] = None,
        window_aggregator: Optional[WindowAggregator] = None,
        late_event_corrector: Optional[LateEventCorrector] = None,
        reconciliation: Optional[StreamingReconciliation] = None,
    ):
        self.enrichment_service = enrichment_service
        self.checkpoint_store = checkpoint_store or CheckpointStore()
        self.duplicate_detector = duplicate_detector or DuplicateDetector()
        self.anomaly_monitor = anomaly_monitor or ContinuousAnomalyMonitor()
        self.window_aggregator = window_aggregator or WindowAggregator()
        self.late_event_corrector = late_event_corrector or LateEventCorrector()
        self.reconciliation = reconciliation or StreamingReconciliation()
        self._processed_count = 0
        self._duplicate_count = 0
        self._correction_count = 0



    def process_event(
        self,
        event: EventEnvelope,
        *,
        internal_records: Sequence[Dict[str, Any]] = (),
        now: Optional[datetime] = None,
    ) -> Dict[str, Any]:
        now = now or datetime.now(timezone.utc)
        if now.tzinfo is None:
            now = now.replace(tzinfo=timezone.utc)
        fingerprint = compute_event_fingerprint(event)
        is_dup, original_id = self.duplicate_detector.check_and_record(
            event.tenant_id, event.event_id, fingerprint, now=now
        )
        if is_dup:
            self._duplicate_count += 1
            return {
                "event_id": event.event_id,
                "tenant_id": event.tenant_id,
                "status": "duplicate",
                "original_event_id": original_id,
                "duplicate": True,
                "processed_at": now.isoformat(),
            }
        if self.late_event_corrector.is_corrected(event.event_id):
            return {
                "event_id": event.event_id,
                "tenant_id": event.tenant_id,
                "status": "corrected_superseded",
                "duplicate": False,
                "processed_at": now.isoformat(),
            }
        enriched = enrich_stream_event(
            event,
            enrichment_service=self.enrichment_service,
        )
        recon_result = self.reconciliation.reconcile_stream_event(
            event,
            internal_records=internal_records,
            now=now,
        )

        self.window_aggregator.add_event(
            tenant_id=event.tenant_id,
            occurred_at=event.occurred_at,
            amount=str(enriched.canonical.amount),
            currency=enriched.canonical.currency,
            status=recon_result.status,
            risk_score=(
                enriched.risk_decision.get("risk_score")
                if enriched.risk_decision
                else None
            ),
        )
        anomaly_indicators = self.anomaly_monitor.observe(
            tenant_id=event.tenant_id,
            occurred_at=event.occurred_at,
            amount=str(enriched.canonical.amount),
            risk_score=(
                enriched.risk_decision.get("risk_score")
                if enriched.risk_decision
                else None
            ),
            now=now,
        )
        if recon_result.status == "EXCEPTION":
            self._correction_count += 1
        self._processed_count += 1
        return {
            "event_id": event.event_id,
            "tenant_id": event.tenant_id,
            "aggregate_id": event.aggregate_id,
            "status": "processed",
            "duplicate": False,
            "enrichment": enriched.enrichment,
            "risk_decision": enriched.risk_decision,
            "reconciliation": {
                "status": recon_result.status,
                "matched_count": len(recon_result.matched_records),
                "unmatched_count": 1 if recon_result.status == "unmatched" else 0,
                "exception_count": 1 if recon_result.status == "exception" else 0,
                "anomalies": recon_result.anomalies,
                "latency_ms": recon_result.latency_ms,
                "reconciliation_id": recon_result.reconciliation_id,
            },
            "anomaly_indicators": [
                {
                    "indicator_id": a.indicator_id,
                    "indicator_type": a.indicator_type,
                    "current_value": a.current_value,
                    "threshold": a.threshold,
                    "breach": a.breach,
                    "window_start": a.window_start,
                    "window_end": a.window_end,
                    "event_count": a.event_count,
                }
                for a in anomaly_indicators
            ],
            "window_snapshot": self.window_aggregator.snapshot(),
            "processed_at": now.isoformat(),
        }



    def checkpoint(self, tenant_id: str, stage: str, offset: int) -> StreamCheckpoint:
        state = {
            "processed_count": self._processed_count,
            "duplicate_count": self._duplicate_count,
            "correction_count": self._correction_count,
            "window_snapshot": self.window_aggregator.snapshot(),
        }
        return self.checkpoint_store.save(
            tenant_id=tenant_id,
            stage=stage,
            last_event_id=f"{tenant_id}:{stage}:{offset}",
            last_event_time=datetime.now(timezone.utc).isoformat(),
            offset=offset,
            state_hash=compute_state_hash(state),
        )

    def metrics(self) -> Dict[str, Any]:
        return {
            "processed_count": self._processed_count,
            "duplicate_count": self._duplicate_count,
            "correction_count": self._correction_count,
            "duplicate_rate": (
                self._duplicate_count / max(self._processed_count + self._duplicate_count, 1)
            ),
            "window_aggregator": self.window_aggregator.snapshot(),
        }



def check_stream_batch_consistency(
    *,
    stream_metrics: Mapping[str, Any],
    batch_metrics: Mapping[str, Any],
    tolerance_percent: float = 0.5,
) -> Dict[str, Any]:
    divergences: list[Dict[str, Any]] = []

    def _compare(name: str, stream_val: Any, batch_val: Any) -> None:
        try:
            s = float(stream_val)
            b = float(batch_val)
        except (TypeError, ValueError):
            if str(stream_val) != str(batch_val):
                divergences.append(
                    {
                        "metric": name,
                        "stream_value": stream_val,
                        "batch_value": batch_val,
                        "divergence": None,
                        "within_tolerance": False,
                    }
                )
            return
        if b == 0:
            within = s == 0
            div = abs(s) if not within else 0.0
        else:
            div = abs(s - b) / abs(b) * 100.0
            within = div <= tolerance_percent
        if not within:
            divergences.append(
                {
                    "metric": name,
                    "stream_value": s,
                    "batch_value": b,
                    "divergence_percent": round(div, 4),
                    "within_tolerance": within,
                }
            )

    _compare("processed_count", stream_metrics.get("processed_count", 0), batch_metrics.get("processed_count", 0))
    _compare("duplicate_count", stream_metrics.get("duplicate_count", 0), batch_metrics.get("duplicate_count", 0))
    stream_windows = stream_metrics.get("window_aggregator", {}).get("windows", [])
    batch_windows = batch_metrics.get("window_aggregator", {}).get("windows", [])
    stream_by_key = {
        (w.get("tenant_id"), w.get("window_start")): w for w in stream_windows
    }
    batch_by_key = {
        (w.get("tenant_id"), w.get("window_start")): w for w in batch_windows
    }
    all_keys = set(stream_by_key) | set(batch_by_key)
    for key in all_keys:
        sw = stream_by_key.get(key)
        bw = batch_by_key.get(key)
        if sw is None:
            divergences.append(
                {
                    "metric": f"window:{key[0]}:{key[1]}",
                    "stream_value": None,
                    "batch_value": bw,
                    "divergence_percent": None,
                    "within_tolerance": False,
                    "reason": "missing in stream",
                }
            )
            continue
        if bw is None:
            divergences.append(
                {
                    "metric": f"window:{key[0]}:{key[1]}",
                    "stream_value": sw,
                    "batch_value": None,
                    "divergence_percent": None,
                    "within_tolerance": False,
                    "reason": "missing in batch",
                }
            )
            continue
        _compare(
            f"window:{key[0]}:{key[1]}:event_count",
            sw.get("event_count"),
            bw.get("event_count"),
        )
        _compare(
            f"window:{key[0]}:{key[1]}:total_amount",
            sw.get("total_amount"),
            bw.get("total_amount"),
        )
    return {
        "consistent": len(divergences) == 0,
        "divergence_count": len(divergences),
        "divergences": divergences,
        "checked_at": datetime.now(timezone.utc).isoformat(),
    }


__all__ = [
    "EnrichedTransaction",
    "enrich_stream_event",
    "StreamingReconciliation",
    "StreamingReconciliationResult",
    "WindowAggregator",
    "WindowBucket",
    "DuplicateDetector",
    "compute_event_fingerprint",
    "ContinuousAnomalyMonitor",
    "AnomalyIndicator",
    "LateEventCorrector",
    "LateEventCorrection",
    "CheckpointStore",
    "StreamCheckpoint",
    "compute_state_hash",
    "ReplaySafeProcessor",
    "check_stream_batch_consistency",
]
