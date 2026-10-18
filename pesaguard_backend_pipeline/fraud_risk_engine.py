"""Layered fraud and anomaly risk engine for transaction decisions."""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timezone
from decimal import Decimal
from statistics import mean, pstdev
from typing import Any, Iterable, Mapping, Sequence

from normalization import (
    DEFAULT_CURRENCY,
    MAX_TRANSACTION_AMOUNT,
    CurrencyMismatchError,
    NormalizationError,
    normalize_amount,
    normalize_currency,
    parse_decimal,
)

MODEL_VERSION = "fraud-rules-v1"
RISK_LEVELS = ("LOW", "MEDIUM", "HIGH", "CRITICAL")


@dataclass(frozen=True)
class FraudFeatures:
    transaction_frequency: float
    amount_deviation: float
    velocity: float
    customer_history: float
    tenant_baseline: float
    time_pattern: float
    amount: float
    customer_id: str
    merchant_id: str

    def as_dict(self) -> dict[str, Any]:
        return {
            "transaction_frequency": self.transaction_frequency,
            "amount_deviation": self.amount_deviation,
            "velocity": self.velocity,
            "customer_history": self.customer_history,
            "tenant_baseline": self.tenant_baseline,
            "time_pattern": self.time_pattern,
            "amount": self.amount,
            "customer_id": self.customer_id,
            "merchant_id": self.merchant_id,
        }


@dataclass(frozen=True)
class RuleResult:
    code: str
    triggered: bool
    weight: float
    reason: str


@dataclass(frozen=True)
class RiskDecision:
    risk_score: float
    risk_level: str
    action: str
    reason_codes: tuple[str, ...]
    model_version: str
    rules_triggered: tuple[str, ...]
    features: FraudFeatures
    detected_at: datetime = field(default_factory=lambda: datetime.now(timezone.utc))
    # Human-readable per-rule explanations for the triggered rules only.
    # Trailing defaulted field: positional construction stays backward-compatible.
    rule_reasons: dict[str, str] = field(default_factory=dict)

    def as_dict(self) -> dict[str, Any]:
        return {
            "risk_score": self.risk_score,
            "risk_level": self.risk_level,
            "action": self.action,
            "priority": risk_priority(self.risk_level, self.rules_triggered),
            "reason_codes": list(self.reason_codes),
            "model_version": self.model_version,
            "rules_triggered": list(self.rules_triggered),
            "rule_reasons": dict(self.rule_reasons),
            "features": self.features.as_dict(),
            "detected_at": self.detected_at.isoformat(),
        }


def _transaction_currency(transaction: Mapping[str, Any]) -> str:
    return normalize_currency(
        transaction.get("Currency")
        or transaction.get("currency")
        or DEFAULT_CURRENCY
    )


def _validated_amount(transaction: Mapping[str, Any], currency: str) -> Decimal:
    raw = transaction.get("TransAmount")
    if raw is None:
        raw = transaction.get("amount")
    return Decimal(normalize_amount(raw, currency=currency))


def _history_for_currency(
    history: Sequence[Mapping[str, Any]],
    currency: str,
) -> list[Mapping[str, Any]]:
    matching = []
    for item in history:
        item_currency = _transaction_currency(item)
        raw_amount = item.get("TransAmount")
        if raw_amount is None:
            raw_amount = item.get("amount")
        if raw_amount is not None:
            _validated_amount(item, item_currency)
        if item_currency == currency:
            matching.append(item)
    return matching


def _baseline_number(
    value: Any,
    currency: str,
    default: float,
    *,
    allow_zero: bool = False,
) -> float:
    if value is None:
        return default
    try:
        number = parse_decimal(value)
    except NormalizationError:
        raise
    if (number < 0 if allow_zero else number <= 0) or abs(number) > MAX_TRANSACTION_AMOUNT:
        raise NormalizationError(f"fraud baseline amount is invalid for {currency}")
    try:
        return float(number)
    except OverflowError as exc:
        raise NormalizationError("fraud baseline amount exceeds scoring range") from exc


def _timestamp(transaction: Mapping[str, Any]) -> datetime | None:
    value = transaction.get("TransTime") or transaction.get("timestamp") or transaction.get("created_at")
    if isinstance(value, datetime):
        return value if value.tzinfo else value.replace(tzinfo=timezone.utc)
    text = str(value or "").strip()
    if len(text) == 14 and text.isdigit():
        try:
            return datetime.strptime(text, "%Y%m%d%H%M%S").replace(tzinfo=timezone.utc)
        except ValueError:
            return None
    try:
        parsed = datetime.fromisoformat(text.replace("Z", "+00:00"))
        return parsed if parsed.tzinfo else parsed.replace(tzinfo=timezone.utc)
    except ValueError:
        return None


def _identity(transaction: Mapping[str, Any], *keys: str) -> str:
    for key in keys:
        value = str(transaction.get(key) or "").strip()
        if value:
            return value
    return "unknown"


def _reference(transaction: Mapping[str, Any]) -> str:
    """Business/reference code attached to a transaction, or '' when absent.

    Unlike ``_identity`` this never falls back to "unknown" so that missing
    references can never masquerade as a shared reference group.
    """
    for key in ("BillRefNumber", "external_reference", "reference", "Reference"):
        value = str(transaction.get(key) or "").strip()
        if value:
            return value
    return ""


_FAILURE_STATUSES = frozenset({"FAILED", "REJECTED", "ERROR"})


def calculate_features(
    transaction: Mapping[str, Any],
    history: Sequence[Mapping[str, Any]] = (),
    tenant_baseline: Mapping[str, Any] | None = None,
) -> FraudFeatures:
    """Calculate bounded, explainable features from transaction and tenant context."""
    baseline = tenant_baseline or {}
    currency = _transaction_currency(transaction)
    amount = float(_validated_amount(transaction, currency))
    customer_id = _identity(transaction, "MSISDN", "customer_id", "customer")
    merchant_id = _identity(transaction, "BusinessShortCode", "merchant_id", "merchant")
    currency_history = _history_for_currency(history, currency)
    customer_history = [
        item for item in currency_history
        if _identity(item, "MSISDN", "customer_id", "customer") == customer_id
    ]
    tenant_amounts = [
        float(_validated_amount(item, currency)) for item in currency_history
    ]
    customer_times = [_timestamp(item) for item in customer_history]
    current_time = _timestamp(transaction)
    recent_customer = [value for value in customer_times if current_time and value and 0 <= (current_time - value).total_seconds() <= 3600]
    frequency = len(recent_customer) / 60.0
    velocity = len([value for value in recent_customer if (current_time - value).total_seconds() <= 60]) if current_time else 0.0
    baseline_currency = normalize_currency(baseline.get("currency") or currency)
    if baseline_currency != currency:
        raise CurrencyMismatchError(
            f"cannot score {currency} transaction against {baseline_currency} baseline"
        )
    mean_amount = _baseline_number(
        baseline.get("mean_amount"),
        currency,
        mean(tenant_amounts) if tenant_amounts else amount,
    )
    std_amount = _baseline_number(
        baseline.get("std_amount"),
        currency,
        pstdev(tenant_amounts) if len(tenant_amounts) > 1 else max(mean_amount * 0.25, 1.0),
        allow_zero=True,
    )
    amount_deviation = min(abs(amount - mean_amount) / max(std_amount, 1.0), 5.0) / 5.0
    customer_history_amounts = [
        float(_validated_amount(item, currency)) for item in customer_history
    ]
    customer_baseline = _baseline_number(
        baseline.get("customer_mean_amount"),
        currency,
        mean(customer_history_amounts) if customer_history_amounts else mean_amount,
    )
    customer_history_score = min(len(customer_history) / 20.0, 1.0)
    tenant_baseline_score = min(abs(amount - mean_amount) / max(mean_amount, 1.0), 5.0) / 5.0
    hour = current_time.hour if current_time else 12
    time_pattern = 1.0 if hour < 5 or hour >= 23 else 0.0
    return FraudFeatures(
        transaction_frequency=min(frequency, 1.0),
        amount_deviation=amount_deviation,
        velocity=min(velocity / 5.0, 1.0),
        customer_history=customer_history_score,
        tenant_baseline=max(tenant_baseline_score, min(abs(amount - customer_baseline) / max(customer_baseline, 1.0), 5.0) / 5.0),
        time_pattern=time_pattern,
        amount=amount,
        customer_id=customer_id,
        merchant_id=merchant_id,
    )


def evaluate_rules(
    transaction: Mapping[str, Any],
    features: FraudFeatures,
    history: Sequence[Mapping[str, Any]] = (),
    *,
    amount_threshold: float = 150_000.0,
    amount_threshold_currency: str = DEFAULT_CURRENCY,
    velocity_limit: float = 5.0,
    reference_limit: int = 3,
    failure_limit: int = 3,
    correlation_min_families: int = 3,
) -> tuple[RuleResult, ...]:
    transaction_id = _identity(transaction, "TransID", "trans_id", "transaction_id")
    currency = _transaction_currency(transaction)
    amount_decimal = _validated_amount(transaction, currency)
    scoped_history = _history_for_currency(history, currency)
    duplicate = any(_identity(item, "TransID", "trans_id", "transaction_id") == transaction_id for item in scoped_history)
    try:
        threshold = parse_decimal(amount_threshold)
    except NormalizationError:
        raise
    if threshold < 0 or threshold > MAX_TRANSACTION_AMOUNT:
        raise NormalizationError("fraud amount threshold is out of range")
    rapid_changes = len({
        float(_validated_amount(item, currency)) for item in scoped_history[-5:]
    }) >= 4
    threshold_currency = normalize_currency(amount_threshold_currency)
    threshold_applies = currency == threshold_currency

    # Repeated transaction reference: the same business reference shared across
    # many *other* distinct transaction identities. Absent references never
    # match, so unrelated reference-less transactions cannot be flagged.
    current_reference = _reference(transaction)
    reused_ids = {
        _identity(item, "TransID", "trans_id", "transaction_id")
        for item in history
        if current_reference
        and _reference(item) == current_reference
        and _identity(item, "TransID", "trans_id", "transaction_id") != transaction_id
    }
    reference_count = len(reused_ids)
    repeated_reference = bool(current_reference) and reference_count >= reference_limit

    # Repeated processing failures: FAILED/REJECTED/ERROR outcomes accumulating
    # for the same customer or merchant across recent history. "unknown"
    # identities never aggregate so unidentifiable rows cannot mass-flag.
    customer_failures = 0
    merchant_failures = 0
    for item in history:
        status = str(item.get("status") or "").strip().upper()
        if status not in _FAILURE_STATUSES:
            continue
        if features.customer_id != "unknown" and _identity(item, "MSISDN", "customer_id", "customer") == features.customer_id:
            customer_failures += 1
        if features.merchant_id != "unknown" and _identity(item, "BusinessShortCode", "merchant_id", "merchant") == features.merchant_id:
            merchant_failures += 1
    failure_pattern = customer_failures >= failure_limit or merchant_failures >= failure_limit

    base_rules = (
        RuleResult("velocity", features.velocity * 5 >= velocity_limit, 0.30, "transaction velocity exceeded the configured limit"),
        RuleResult(
            "amount_threshold",
            threshold_applies and amount_decimal > threshold,
            0.20,
            "transaction amount exceeded the tenant threshold"
            if threshold_applies
            else f"amount threshold is denominated in {threshold_currency}",
        ),
        RuleResult("duplicate_activity", duplicate, 0.30, "transaction identity was already observed"),
        RuleResult("unusual_time", features.time_pattern > 0, 0.10, "transaction occurred during an unusual time window"),
        RuleResult("rapid_transaction_changes", rapid_changes, 0.15, "recent transaction amounts changed rapidly"),
        RuleResult(
            "repeated_reference",
            repeated_reference,
            0.25,
            f"the same transaction reference was reused across {reference_count} other distinct transactions"
            if repeated_reference
            else "no repeated transaction reference reuse observed",
        ),
        RuleResult(
            "processing_failure_pattern",
            failure_pattern,
            0.25,
            "repeated processing failures observed for the same customer or merchant "
            f"(customer_failures={customer_failures}, merchant_failures={merchant_failures})"
            if failure_pattern
            else "no repeated processing failure pattern observed",
        ),
    )

    # Cross-event correlation is only justified when several independent
    # signal families fire together on the same transaction; a single signal
    # and its variants (reference identity) never count as correlation.
    families: set[str] = set()
    for rule in base_rules:
        if not rule.triggered:
            continue
        if rule.code == "velocity":
            families.add("velocity")
        elif rule.code == "amount_threshold":
            families.add("amount")
        elif rule.code in {"duplicate_activity", "repeated_reference"}:
            families.add("reference")
        elif rule.code == "unusual_time":
            families.add("time")
        elif rule.code == "rapid_transaction_changes":
            families.add("amount_change")
        elif rule.code == "processing_failure_pattern":
            families.add("failure")
    correlated = len(families) >= max(2, correlation_min_families)
    correlation_rule = RuleResult(
        "correlated_indicators",
        correlated,
        0.15,
        f"{len(families)} independent indicator families correlated ({', '.join(sorted(families))})"
        if correlated
        else f"only {len(families)} independent indicator families observed",
    )
    return (*base_rules, correlation_rule)


def _risk_level(score: float) -> str:
    if score >= 0.85:
        return "CRITICAL"
    if score >= 0.65:
        return "HIGH"
    if score >= 0.35:
        return "MEDIUM"
    return "LOW"


def _action(level: str) -> str:
    return {"LOW": "process", "MEDIUM": "monitor", "HIGH": "review", "CRITICAL": "escalate"}[level]


_PRIORITY_BY_LEVEL = {"CRITICAL": "P1", "HIGH": "P2", "MEDIUM": "P3", "LOW": "P4"}
_PRIORITY_ORDER = ("P4", "P3", "P2", "P1")


def risk_priority(risk_level: str, rules_triggered: Iterable[str] = ()) -> str:
    """Map a risk level to an operational triage priority.

    P1 is the highest operational priority. When independent indicator
    families are correlated on the same transaction the priority escalates
    one step (never above P1) because corroborating signals justify faster
    triage than any single indicator alone.
    """
    try:
        base = _PRIORITY_BY_LEVEL[risk_level]
    except KeyError:
        raise ValueError(f"unknown risk level: {risk_level!r}") from None
    if "correlated_indicators" in set(rules_triggered):
        index = _PRIORITY_ORDER.index(base)
        return _PRIORITY_ORDER[min(index + 1, len(_PRIORITY_ORDER) - 1)]
    return base


def assess_transaction(
    transaction: Mapping[str, Any],
    history: Sequence[Mapping[str, Any]] = (),
    tenant_baseline: Mapping[str, Any] | None = None,
    *,
    model_version: str = MODEL_VERSION,
    amount_threshold: float = 150_000.0,
    amount_threshold_currency: str = DEFAULT_CURRENCY,
    velocity_limit: float = 5.0,
    reference_limit: int = 3,
    failure_limit: int = 3,
    correlation_min_families: int = 3,
) -> RiskDecision:
    features = calculate_features(transaction, history, tenant_baseline)
    rules = evaluate_rules(
        transaction,
        features,
        history,
        amount_threshold=amount_threshold,
        amount_threshold_currency=amount_threshold_currency,
        velocity_limit=velocity_limit,
        reference_limit=reference_limit,
        failure_limit=failure_limit,
        correlation_min_families=correlation_min_families,
    )
    feature_score = (
        features.amount_deviation * 0.25
        + features.velocity * 0.25
        + features.tenant_baseline * 0.20
        + features.time_pattern * 0.10
        + min(features.customer_history, 1.0) * 0.10
    )
    rule_score = sum(rule.weight for rule in rules if rule.triggered)
    score = round(min(1.0, max(feature_score, rule_score) + min(feature_score * rule_score, 0.25)), 4)
    triggered = tuple(rule.code for rule in rules if rule.triggered)
    rule_reasons = {rule.code: rule.reason for rule in rules if rule.triggered}
    level = _risk_level(score)
    return RiskDecision(
        score,
        level,
        _action(level),
        triggered,
        model_version,
        triggered,
        features,
        rule_reasons=rule_reasons,
    )


def persist_assessment(session: Any, tenant_id: str, transaction_id: str, decision: RiskDecision) -> Any:
    """Upsert the explainable decision for a tenant-scoped transaction."""
    from models import FraudRiskAssessment

    assessment = session.query(FraudRiskAssessment).filter_by(
        tenant_id=tenant_id,
        transaction_id=transaction_id,
    ).one_or_none()
    values = {
        "risk_score": decision.risk_score,
        "risk_level": decision.risk_level,
        "action": decision.action,
        "reason_codes": list(decision.reason_codes),
        "model_version": decision.model_version,
        "rules_triggered": list(decision.rules_triggered),
        "features": decision.features.as_dict(),
    }
    if assessment is None:
        assessment = FraudRiskAssessment(tenant_id=tenant_id, transaction_id=transaction_id, **values)
        session.add(assessment)
    else:
        for key, value in values.items():
            setattr(assessment, key, value)
    session.flush()
    return assessment


def evaluate_predictions(predictions: Iterable[tuple[RiskDecision, bool]]) -> dict[str, float]:
    """Measure precision, recall, FP/FN counts, and average detection latency."""
    rows = list(predictions)
    true_positive = false_positive = false_negative = true_negative = 0
    latencies: list[float] = []
    for decision, actual_fraud in rows:
        predicted = decision.risk_level in {"HIGH", "CRITICAL"}
        if predicted and actual_fraud:
            true_positive += 1
        elif predicted:
            false_positive += 1
        elif actual_fraud:
            false_negative += 1
        else:
            true_negative += 1
        latency = (datetime.now(timezone.utc) - decision.detected_at).total_seconds() * 1000
        latencies.append(max(0.0, latency))
    return {
        "precision": true_positive / max(true_positive + false_positive, 1),
        "recall": true_positive / max(true_positive + false_negative, 1),
        "false_positives": float(false_positive),
        "false_negatives": float(false_negative),
        "true_negatives": float(true_negative),
        "detection_latency_ms": sum(latencies) / len(latencies) if latencies else 0.0,
    }
