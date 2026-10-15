"""Incremental and backfill-safe merchant aggregation services."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from decimal import Decimal
from typing import Any, Dict, Iterable, Mapping
from uuid import uuid4

from models import MerchantMetric
from normalization import (
    DEFAULT_CURRENCY,
    normalize_amount,
    normalize_currency,
    quantize_money,
)


@dataclass
class MerchantAggregate:
    merchant_id: str
    currency: str
    transaction_count: int = 0
    total_volume: Decimal = Decimal("0")
    failed_transactions: int = 0
    reconciled_transactions: int = 0
    anomaly_count: int = 0

    @property
    def average_transaction(self) -> Decimal:
        return (
            quantize_money(
                self.total_volume / self.transaction_count,
                currency=self.currency,
            )
            if self.transaction_count
            else quantize_money(Decimal("0"), currency=self.currency)
        )

    @property
    def failure_rate(self) -> float:
        return self.failed_transactions / self.transaction_count if self.transaction_count else 0.0

    @property
    def reconciliation_rate(self) -> float:
        return self.reconciled_transactions / self.transaction_count if self.transaction_count else 0.0

    @property
    def anomaly_rate(self) -> float:
        return self.anomaly_count / self.transaction_count if self.transaction_count else 0.0


def _value(record: Mapping[str, Any], *keys: str, default: Any = None) -> Any:
    for key in keys:
        if record.get(key) is not None:
            return record[key]
    return default


def _amount(record: Mapping[str, Any], currency: str) -> Decimal:
    """Validate an amount for aggregation; invalid input must not become zero."""
    raw = _value(record, "amount", "TransAmount", default=None)
    if raw is None:
        raise ValueError("aggregated transaction amount is required")
    return Decimal(normalize_amount(raw, currency=currency))


def aggregate_transactions(records: Iterable[Mapping[str, Any]]) -> Dict[tuple[str, str], MerchantAggregate]:
    """Build separate merchant aggregates for each currency."""
    aggregates: Dict[tuple[str, str], MerchantAggregate] = {}
    for record in records:
        merchant_id = str(_value(record, "merchant_id", "merchant", "account_id", "provider_account_id", default="unknown"))
        currency = normalize_currency(
            _value(record, "currency", "Currency", default=DEFAULT_CURRENCY)
        )
        key = (merchant_id, currency)
        aggregate = aggregates.setdefault(
            key,
            MerchantAggregate(merchant_id=merchant_id, currency=currency),
        )
        aggregate.transaction_count += 1
        aggregate.total_volume += _amount(record, currency)
        status = str(_value(record, "status", "Status", default="")).upper()
        if status in {"FAILED", "REJECTED", "REVERSED"}:
            aggregate.failed_transactions += 1
        if status in {"RECONCILED", "MATCHED", "COMPLETED"} or str(record.get("reconciliation_status", "")).lower() == "completed":
            aggregate.reconciled_transactions += 1
        if record.get("anomaly") or record.get("anomalies") or str(record.get("risk_level", "")).upper() in {"HIGH", "CRITICAL"}:
            aggregate.anomaly_count += 1
    return aggregates


def _parse_time(value: Any) -> datetime:
    parsed = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
    return parsed.astimezone(timezone.utc) if parsed.tzinfo else parsed.replace(tzinfo=timezone.utc)


def _bucket_start(value: datetime, granularity: str) -> datetime:
    value = value.astimezone(timezone.utc)
    if granularity == "hour":
        return value.replace(minute=0, second=0, microsecond=0)
    if granularity == "day":
        return value.replace(hour=0, minute=0, second=0, microsecond=0)
    if granularity == "week":
        day_start = value.replace(hour=0, minute=0, second=0, microsecond=0)
        return day_start - timedelta(days=day_start.weekday())
    raise ValueError(f"unsupported metric granularity: {granularity}")


def update_merchant_metrics(session: Any, record: Mapping[str, Any], *, tenant_id: str, granularities: tuple[str, ...] = ("hour", "day", "week")) -> list[MerchantMetric]:
    """Increment persisted buckets once for one canonical transaction."""
    merchant_id = str(_value(record, "merchant_id", "merchant", "account_id", "provider_account_id", default="unknown"))
    transaction_time = _parse_time(_value(record, "transaction_time", "TransTime", default=datetime.now(timezone.utc).isoformat()))
    currency = normalize_currency(
        _value(record, "currency", "Currency", default=DEFAULT_CURRENCY)
    )
    amount = _amount(record, currency)
    status = str(_value(record, "status", "Status", default="")).upper()
    reconciled = status in {"RECONCILED", "MATCHED", "COMPLETED"} or str(record.get("reconciliation_status", "")).lower() == "completed"
    failed = status in {"FAILED", "REJECTED", "REVERSED"}
    anomaly = bool(record.get("anomaly") or record.get("anomalies") or str(record.get("risk_level", "")).upper() in {"HIGH", "CRITICAL"})
    updated = []
    for granularity in granularities:
        bucket = _bucket_start(transaction_time, granularity)
        metric = session.query(MerchantMetric).filter_by(
            tenant_id=tenant_id,
            merchant_id=merchant_id,
            currency=currency,
            granularity=granularity,
            bucket_start=bucket,
        ).with_for_update().first()
        if metric is None:
            metric = MerchantMetric(
                id=f"metric_{uuid4().hex}",
                tenant_id=tenant_id,
                merchant_id=merchant_id,
                currency=currency,
                granularity=granularity,
                bucket_start=bucket,
            )
            session.add(metric)
        metric.transaction_count = (metric.transaction_count or 0) + 1
        metric.total_volume = Decimal(metric.total_volume or 0) + amount
        metric.average_transaction = quantize_money(
            Decimal(metric.total_volume) / metric.transaction_count,
            currency=currency,
        )
        metric.failed_transactions = (metric.failed_transactions or 0) + int(failed)
        metric.reconciled_transactions = (metric.reconciled_transactions or 0) + int(reconciled)
        metric.anomaly_count = (metric.anomaly_count or 0) + int(anomaly)
        metric.updated_at = datetime.now(timezone.utc)
        updated.append(metric)
    return updated