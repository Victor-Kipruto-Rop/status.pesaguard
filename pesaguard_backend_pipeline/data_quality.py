"""Phase 9 data quality.

Defines the DQ rule contract, result/status types, and the run_data_quality
orchestrator used by the provenance layer.
"""

from __future__ import annotations

import hashlib
import json
import math
import os
from dataclasses import dataclass, field
from datetime import datetime, timezone
from decimal import Decimal, InvalidOperation
from statistics import median
from typing import Any, Dict, List, Mapping, Optional, Sequence

from normalization import (
    NormalizationError,
    normalize_amount,
    normalize_currency,
    normalize_timestamp,
)

# Currencies and providers recognised by the DQ rule set. Shared by the rule
# implementations (dq_rules) and the orchestrator so there is one allowlist.
DQ_CURRENCY_CODES = frozenset({"KES", "USD"})
DQ_PROVIDER_ALLOWLIST = frozenset({"mpesa", "safaricom", "daraja", "africas_talking"})

LINEAGE_KEYS = ("lineage_id", "tenant_id", "event_id", "transaction_id", "provider_id")

DQ_DIMENSIONS = ["completeness", "uniqueness", "validity", "consistency", "accuracy", "timeliness"]
DQ_SCORING_VERSION = "equal_dimension_rule_mean_v1"
DQ_SCORE_PRECISION = 4
MAX_DQ_BATCH_SIZE = 5_000
MAX_REFERENCE_RECORDS = 5_000
DEFAULT_OUTLIER_MINIMUM_SAMPLE_SIZE = 20
DEFAULT_OUTLIER_MODIFIED_Z_THRESHOLD = Decimal("3.5")
MAX_FUTURE_TIMESTAMP_SKEW_SECONDS = 300

_RULE_DIMENSIONS = {
    "_rule_transaction_id_not_null": "completeness",
    "_rule_amount_positive": "validity",
    "_rule_currency_valid": "validity",
    "_rule_provider_valid": "validity",
    "_rule_reference_valid": "consistency",
    "_rule_tenant_exists": "consistency",
    "_rule_schema_version_present": "consistency",
}

_RULE_DESCRIPTIONS = {
    "_rule_transaction_id_not_null": "Transaction identifier must be present",
    "_rule_amount_positive": "Transaction amount must be greater than zero",
    "_rule_currency_valid": "Currency must be supported",
    "_rule_provider_valid": "Provider must be recognized",
    "_rule_reference_valid": "Reference identifier must be present where required",
    "_rule_tenant_exists": "Tenant identifier must be present",
    "_rule_schema_version_present": "Payload must declare a schema version",
}


@dataclass(frozen=True)
class DataQualityRule:
    code: str
    dimension: str
    description: str
    expression: str


@dataclass(frozen=True)
class DataQualityFailure:
    rule: DataQualityRule
    value: Any = None
    reason: str = ""


@dataclass(frozen=True)
class DataQualityCheck:
    code: str
    dimension: str
    passed: bool
    blocking: bool
    applicable: bool = True
    description: str = ""
    expression: str = ""
    value: Any = None
    reason: str = ""

    def to_dict(self) -> Dict[str, Any]:
        return {
            "rule": self.code,
            "dimension": self.dimension,
            "passed": self.passed,
            "blocking": self.blocking,
            "applicable": self.applicable,
            "description": self.description,
            "expression": self.expression,
            "value": str(self.value)[:512] if self.value is not None else None,
            "reason": self.reason,
        }


@dataclass
class DataQualityResult:
    status: str
    transaction_id: str
    tenant_id: str
    provider_id: str
    lineage_id: str
    schema_version: Optional[str] = None
    pipeline_version: Optional[str] = None
    failures: List[DataQualityFailure] = field(default_factory=list)
    warnings: List[DataQualityFailure] = field(default_factory=list)
    checks: List[DataQualityCheck] = field(default_factory=list)
    raw_payload_hash: Optional[str] = None
    profile: Optional[Dict[str, Any]] = None
    lineage_snapshot: Optional[Dict[str, Any]] = None

    @property
    def is_ok(self) -> bool:
        return self.status == DataQualityStatus.PASS

    @property
    def dimension_scores(self) -> Dict[str, float]:
        """Score each dimension as passing applicable rules / applicable rules."""
        scores: Dict[str, float] = {}
        for dimension in DQ_DIMENSIONS:
            checks = [
                check
                for check in self.checks
                if check.dimension == dimension and check.applicable
            ]
            score = (
                sum(check.passed for check in checks) / len(checks)
                if checks
                else 1.0
            )
            scores[dimension] = round(score, DQ_SCORE_PRECISION)
        return scores

    @property
    def overall_score(self) -> float:
        """Return the equal-weight arithmetic mean of all dimension scores."""
        return round(
            sum(self.dimension_scores.values()) / len(DQ_DIMENSIONS),
            DQ_SCORE_PRECISION,
        )

    @property
    def completeness(self) -> float:
        return self.dimension_scores["completeness"]

    @property
    def uniqueness(self) -> float:
        return self.dimension_scores["uniqueness"]

    @property
    def validity(self) -> float:
        return self.dimension_scores["validity"]

    @property
    def consistency(self) -> float:
        return self.dimension_scores["consistency"]

    @property
    def accuracy(self) -> float:
        return self.dimension_scores["accuracy"]

    @property
    def timeliness(self) -> float:
        return self.dimension_scores["timeliness"]

    def to_dict(self) -> Dict[str, Any]:
        return {
            "status": self.status,
            "transaction_id": self.transaction_id,
            "tenant_id": self.tenant_id,
            "provider_id": self.provider_id,
            "lineage_id": self.lineage_id,
            "schema_version": self.schema_version,
            "pipeline_version": self.pipeline_version,
            "failures": [self._failure_dict(f) for f in self.failures],
            "warnings": [self._failure_dict(f) for f in self.warnings],
            "checks": [check.to_dict() for check in self.checks],
            "dimension_scores": self.dimension_scores,
            "overall_score": self.overall_score,
            "raw_payload_hash": self.raw_payload_hash,
            "profile": self.profile,
            "lineage_snapshot": self.lineage_snapshot,
        }

    @staticmethod
    def _failure_dict(failure: DataQualityFailure) -> Dict[str, Any]:
        return {
            "rule": failure.rule.code,
            "dimension": failure.rule.dimension,
            "description": failure.rule.description,
            "expression": failure.rule.expression,
            "value": str(failure.value)[:512] if failure.value is not None else None,
            "reason": failure.reason,
        }


class DataQualityStatus:
    PASS = "pass"
    FAIL = "fail"


def tx_id_from_raw(raw: Dict[str, Any]) -> str:
    for key in ("TransID", "TransactionID", "transaction_id"):
        value = raw.get(key)
        if isinstance(value, str) and value.strip():
            return value.strip()
        if value is not None:
            return str(value)
    return ""


def tenant_id_from_raw(raw: Dict[str, Any]) -> str:
    for key in ("TenantID", "tenant_id", "tenantId"):
        value = raw.get(key)
        if isinstance(value, str) and value.strip():
            return value.strip()
        if value is not None:
            return str(value)
    return ""


def provider_id_from_raw(raw: Dict[str, Any]) -> str:
    for key in ("Provider", "provider", "source", "provider_id"):
        value = raw.get(key)
        if isinstance(value, str) and value.strip():
            return value.strip().lower()
        if value is not None:
            return str(value)
    return ""


def run_data_quality(
    raw: Mapping[str, Any],
    *,
    lineage: Optional[Any] = None,
    schema_version: Optional[str] = None,
    pipeline_version: Optional[str] = None,
    expected_schema_version: Optional[str] = None,
    now: Optional[datetime] = None,
    freshness_max_age_seconds: Optional[float] = None,
    reference_records: Optional[Sequence[Mapping[str, Any]]] = None,
    outlier_minimum_sample_size: int = DEFAULT_OUTLIER_MINIMUM_SAMPLE_SIZE,
    outlier_modified_z_threshold: Decimal = DEFAULT_OUTLIER_MODIFIED_Z_THRESHOLD,
) -> DataQualityResult:
    """Run the transaction rule set and return a serializable DQ result.

    When ``lineage`` is provided, the result carries the lineage snapshot so the
    provenance layer can persist the full exit-gate picture.
    """
    from data_lineage import build_lineage

    if not isinstance(raw, Mapping):
        raise TypeError("data-quality payload must be a mapping")
    raw = dict(raw)
    now = now or datetime.now(timezone.utc)
    if now.tzinfo is None:
        raise ValueError("data-quality reference time must be timezone-aware")
    if freshness_max_age_seconds is not None and (
        isinstance(freshness_max_age_seconds, bool)
        or not math.isfinite(float(freshness_max_age_seconds))
        or freshness_max_age_seconds <= 0
    ):
        raise ValueError("freshness_max_age_seconds must be a positive finite number")
    if (
        isinstance(outlier_minimum_sample_size, bool)
        or outlier_minimum_sample_size < 3
    ):
        raise ValueError("outlier_minimum_sample_size must be at least 3")
    threshold = Decimal(str(outlier_modified_z_threshold))
    if not threshold.is_finite() or threshold <= 0:
        raise ValueError("outlier_modified_z_threshold must be positive and finite")
    if reference_records is not None and len(reference_records) > MAX_REFERENCE_RECORDS:
        raise ValueError(f"reference_records exceeds the maximum of {MAX_REFERENCE_RECORDS}")
    if reference_records is not None and any(
        not isinstance(record, Mapping) for record in reference_records
    ):
        raise TypeError("every DQ reference record must be a mapping")

    transaction_id = tx_id_from_raw(raw) or "unknown"
    tenant_id = tenant_id_from_raw(raw) or "unknown"
    provider_id = provider_id_from_raw(raw) or "unknown"

    if lineage is None:
        lineage = build_lineage(
            event_id=raw.get("event_id") or raw.get("TransactionType") or "event",
            transaction_id=transaction_id,
            tenant_id=tenant_id,
            provider_id=provider_id,
        )
    lineage_id = lineage.lineage_id

    from dq_rules import (
        _rule_amount_positive,
        _rule_currency_valid,
        _rule_provider_valid,
        _rule_reference_valid,
        _rule_schema_version_present,
        _rule_tenant_exists,
        _rule_transaction_id_not_null,
    )

    normalized = _normalize_payload(raw)
    rules = [
        _rule_transaction_id_not_null,
        _rule_amount_positive,
        _rule_currency_valid,
        _rule_provider_valid,
        _rule_reference_valid,
        _rule_tenant_exists,
        _rule_schema_version_present,
    ]

    failures: List[DataQualityFailure] = []
    warnings: List[DataQualityFailure] = []
    checks: List[DataQualityCheck] = []
    for rule_fn in rules:
        passed, failure = rule_fn(raw, normalized)
        code = rule_fn.__name__
        rule = failure.rule if failure else None
        _record_check(
            checks,
            failures,
            warnings,
            code=rule.code if rule else code.removeprefix("_rule_"),
            dimension=rule.dimension if rule else _RULE_DIMENSIONS[code],
            passed=passed,
            blocking=True,
            description=rule.description if rule else _RULE_DESCRIPTIONS[code],
            expression=rule.expression if rule else "",
            value=failure.value if failure else None,
            reason=failure.reason if failure else "",
            applicable=not (
                rule_fn.__name__ == "_rule_reference_valid"
                and not any(
                    key in raw
                    for key in (
                        "BillRefNumber",
                        "BillReferenceNumber",
                        "reference",
                        "reference_number",
                        "AccountReference",
                    )
                )
            ),
        )

    checks.append(
        DataQualityCheck(
            code="duplicate_transaction",
            dimension="uniqueness",
            passed=True,
            blocking=True,
            applicable=False,
            description="Transaction identifier is unique within tenant/provider scope",
            expression="count(tenant_id, provider_id, transaction_id) = 1",
            reason="requires batch or scoped historical comparison",
        )
    )
    _add_timestamp_checks(raw, now, freshness_max_age_seconds, checks, failures, warnings)
    if expected_schema_version is not None:
        from schema_evolution import assess_schema_compatibility, schema_version_from_payload

        incoming_schema_version = schema_version_from_payload(raw)
        if incoming_schema_version:
            compatibility, compatibility_note = assess_schema_compatibility(
                expected_schema_version,
                incoming_schema_version,
            )
            supported = compatibility != "incompatible"
            _record_check(
                checks,
                failures,
                warnings,
                code="schema_contract_compatible",
                dimension="consistency",
                passed=compatibility == "compatible",
                blocking=not supported,
                description="Incoming schema version must be compatible with the active contract",
                expression="schema_compatibility != incompatible",
                value=incoming_schema_version,
                reason=(
                    f"schema_incompatible: {compatibility_note}"
                    if not supported
                    else compatibility_note
                    if compatibility == "migration_required"
                    else ""
                ),
            )
    _add_amount_consistency_check(raw, checks, failures, warnings)
    _add_outlier_check(
        raw,
        normalized,
        reference_records or (),
        outlier_minimum_sample_size,
        threshold,
        checks,
        failures,
        warnings,
    )

    status = DataQualityStatus.FAIL if failures else DataQualityStatus.PASS
    profile = _profile_payload(raw)
    profile["freshness"] = _freshness_profile(
        raw,
        now,
        freshness_max_age_seconds,
    )
    raw_payload_hash = _json_hash(raw)
    lineage_snapshot = lineage.to_dict() if hasattr(lineage, "to_dict") else {}

    return DataQualityResult(
        status=status,
        transaction_id=transaction_id,
        tenant_id=tenant_id,
        provider_id=provider_id,
        lineage_id=lineage_id,
        schema_version=schema_version,
        pipeline_version=pipeline_version,
        failures=failures,
        warnings=warnings,
        checks=checks,
        raw_payload_hash=raw_payload_hash,
        profile=profile,
        lineage_snapshot=lineage_snapshot,
    )


def dq_report(result: DataQualityResult) -> Dict[str, Any]:
    """Human-consumable DQ report for lineage and audit context."""
    return {
        "status": result.status,
        "dimensions": DQ_DIMENSIONS,
        "dimension_scores": result.dimension_scores,
        "overall_score": result.overall_score,
        "scoring": {
            "version": DQ_SCORING_VERSION,
            "dimension_formula": (
                "passing applicable checks / applicable checks; equal weight per rule"
            ),
            "overall_formula": "arithmetic mean of the six dimension scores",
            "rounding_decimal_places": DQ_SCORE_PRECISION,
            "overall_score": result.overall_score,
        },
        "checks": [check.to_dict() for check in result.checks],
        "failures": result.to_dict()["failures"],
        "warnings": result.to_dict()["warnings"],
        "profile": result.profile,
        "transaction_id": result.transaction_id,
        "tenant_id": result.tenant_id,
        "provider_id": result.provider_id,
        "schema_version": result.schema_version,
        "pipeline_version": result.pipeline_version,
    }


@dataclass
class DataQualityBatchResult:
    results: List[DataQualityResult]
    duplicate_count: int

    @property
    def status(self) -> str:
        return (
            DataQualityStatus.FAIL
            if any(result.status == DataQualityStatus.FAIL for result in self.results)
            else DataQualityStatus.PASS
        )

    @property
    def overall_score(self) -> float:
        if not self.results:
            return 1.0
        return round(
            sum(result.overall_score for result in self.results) / len(self.results),
            DQ_SCORE_PRECISION,
        )


def run_data_quality_batch(
    records: Sequence[Mapping[str, Any]],
    *,
    existing_records: Optional[Sequence[Mapping[str, Any]]] = None,
    **quality_options: Any,
) -> DataQualityBatchResult:
    """Evaluate a bounded batch and detect duplicate IDs within tenant/provider scope."""
    if not isinstance(records, Sequence) or isinstance(records, (str, bytes)):
        raise TypeError("data-quality batch must be a sequence of mappings")
    if len(records) > MAX_DQ_BATCH_SIZE:
        raise ValueError(f"data-quality batch exceeds maximum batch size of {MAX_DQ_BATCH_SIZE}")
    if any(not isinstance(record, Mapping) for record in records):
        raise TypeError("every data-quality batch record must be a mapping")

    existing = existing_records or ()
    if not isinstance(existing, Sequence) or isinstance(existing, (str, bytes)):
        raise TypeError("existing_records must be a sequence of mappings")
    if len(existing) > MAX_DQ_BATCH_SIZE:
        raise ValueError(f"existing_records exceeds maximum size of {MAX_DQ_BATCH_SIZE}")
    if any(not isinstance(record, Mapping) for record in existing):
        raise TypeError("every existing record must be a mapping")
    supplied_references = quality_options.pop(
        "reference_records",
        tuple(existing) + tuple(records),
    )
    evaluated = [
        run_data_quality(
            record,
            reference_records=supplied_references,
            **quality_options,
        )
        for record in records
    ]
    first_by_key: Dict[tuple[str, str, str], tuple[int, str]] = {}
    record_indexes_by_key: Dict[tuple[str, str, str], List[int]] = {}
    duplicate_count = 0
    for index, record in enumerate(existing):
        key = _duplicate_key(record)
        if key is not None:
            if key not in first_by_key:
                first_by_key[key] = (index, _json_hash(dict(record)))
            record_indexes_by_key.setdefault(key, []).append(index)
    for index, (record, result) in enumerate(zip(records, evaluated)):
        key = _duplicate_key(record)
        if key is None:
            continue
        previous = first_by_key.get(key)
        if previous is None:
            first_by_key[key] = (index, result.raw_payload_hash or "")
            record_indexes_by_key.setdefault(key, []).append(len(existing) + index)
            continue
        duplicate_count += 1
        _, previous_hash = previous
        identical = previous_hash == (result.raw_payload_hash or "")
        if not identical:
            for previous_index in record_indexes_by_key[key]:
                if previous_index >= len(existing):
                    _add_duplicate_result(
                        evaluated[previous_index - len(existing)],
                        identical=False,
                    )
        _add_duplicate_result(
            result,
            identical=identical,
        )
        record_indexes_by_key[key].append(len(existing) + index)

    return DataQualityBatchResult(results=evaluated, duplicate_count=duplicate_count)


def dq_batch_report(result: DataQualityBatchResult) -> Dict[str, Any]:
    """Return a bounded batch summary and record-level DQ evidence."""
    warning_counts: Dict[str, int] = {}
    for record in result.results:
        for warning in record.warnings:
            warning_counts[warning.rule.code] = warning_counts.get(warning.rule.code, 0) + 1
    return {
        "status": result.status,
        "record_count": len(result.results),
        "duplicate_count": result.duplicate_count,
        "overall_score": result.overall_score,
        "scoring_version": DQ_SCORING_VERSION,
        "summary": {
            "failed_record_count": sum(
                record.status == DataQualityStatus.FAIL for record in result.results
            ),
            "warning_counts": dict(sorted(warning_counts.items())),
            "stale_record_count": sum(
                (record.profile or {}).get("freshness", {}).get("status") == "stale"
                for record in result.results
            ),
        },
        "records": [dq_report(record) for record in result.results],
    }


def _record_check(
    checks: List[DataQualityCheck],
    failures: List[DataQualityFailure],
    warnings: List[DataQualityFailure],
    *,
    code: str,
    dimension: str,
    passed: bool,
    blocking: bool,
    description: str,
    expression: str,
    value: Any = None,
    reason: str = "",
    applicable: bool = True,
) -> None:
    checks.append(
        DataQualityCheck(
            code=code,
            dimension=dimension,
            passed=passed,
            blocking=blocking,
            applicable=applicable,
            description=description,
            expression=expression,
            value=value,
            reason=reason,
        )
    )
    if passed or not applicable:
        return
    failure = DataQualityFailure(
        rule=DataQualityRule(code, dimension, description, expression),
        value=value,
        reason=reason,
    )
    (failures if blocking else warnings).append(failure)


def _timestamp_value(raw: Mapping[str, Any]) -> Any:
    for key in ("TransTime", "TransactionTime", "transaction_time", "timestamp"):
        if key in raw:
            return raw[key]
    return None


def _add_timestamp_checks(
    raw: Mapping[str, Any],
    now: datetime,
    freshness_max_age_seconds: Optional[float],
    checks: List[DataQualityCheck],
    failures: List[DataQualityFailure],
    warnings: List[DataQualityFailure],
) -> None:
    value = _timestamp_value(raw)
    if value is None or (isinstance(value, str) and not value.strip()):
        _record_check(
            checks, failures, warnings,
            code="transaction_time_required",
            dimension="completeness",
            passed=False,
            blocking=True,
            description="Transaction timestamp must be present",
            expression="transaction_time IS NOT NULL",
            reason="transaction timestamp is missing or empty",
        )
        _record_check(
            checks, failures, warnings,
            code="transaction_time_valid",
            dimension="validity",
            passed=True,
            blocking=True,
            description="Transaction timestamp must be parseable and not in the future",
            expression="transaction_time is a supported timestamp <= now + 300 seconds",
            applicable=False,
        )
        _record_check(
            checks, failures, warnings,
            code="event_freshness",
            dimension="timeliness",
            passed=True,
            blocking=False,
            description="Transaction age is within the configured limit",
            expression="event_age_seconds <= freshness_max_age_seconds",
            applicable=False,
        )
        return
    try:
        if not isinstance(value, (str, datetime)):
            raise NormalizationError("timestamp must be text or datetime")
        normalized = (
            value.astimezone(timezone.utc).isoformat()
            if isinstance(value, datetime) and value.tzinfo is not None
            else value.isoformat()
            if isinstance(value, datetime)
            else value
        )
        parsed = datetime.fromisoformat(
            normalize_timestamp(normalized, default_timezone="UTC").replace("Z", "+00:00")
        )
        current = now.astimezone(timezone.utc)
        age_seconds = (current - parsed).total_seconds()
        if age_seconds < -MAX_FUTURE_TIMESTAMP_SKEW_SECONDS:
            raise NormalizationError("transaction timestamp is in the future")
    except (NormalizationError, TypeError, ValueError, OverflowError) as exc:
        _record_check(
            checks, failures, warnings,
            code="transaction_time_valid",
            dimension="validity",
            passed=False,
            blocking=True,
            description="Transaction timestamp must be parseable and not in the future",
            expression="transaction_time is a supported timestamp <= now + 300 seconds",
            value=str(value)[:128],
            reason=str(exc),
        )
        _record_check(
            checks, failures, warnings,
            code="event_freshness",
            dimension="timeliness",
            passed=True,
            blocking=False,
            description="Transaction age is within the configured limit",
            expression="event_age_seconds <= freshness_max_age_seconds",
            applicable=False,
        )
        return

    _record_check(
        checks, failures, warnings,
        code="transaction_time_required",
        dimension="completeness",
        passed=True,
        blocking=True,
        description="Transaction timestamp must be present",
        expression="transaction_time IS NOT NULL",
    )
    _record_check(
        checks, failures, warnings,
        code="transaction_time_valid",
        dimension="validity",
        passed=True,
        blocking=True,
        description="Transaction timestamp must be parseable and not in the future",
        expression="transaction_time is a supported timestamp <= now + 300 seconds",
    )
    stale = (
        freshness_max_age_seconds is not None
        and age_seconds > freshness_max_age_seconds
    )
    _record_check(
        checks, failures, warnings,
        code="event_freshness",
        dimension="timeliness",
        passed=not stale,
        blocking=False,
        description="Transaction age is within the configured limit",
        expression="event_age_seconds <= freshness_max_age_seconds",
        value=round(age_seconds, 3),
        reason=(
            f"event age {round(age_seconds, 3)}s exceeds configured limit "
            f"{freshness_max_age_seconds}s"
            if stale
            else ""
        ),
        applicable=freshness_max_age_seconds is not None,
    )


def _add_amount_consistency_check(
    raw: Mapping[str, Any],
    checks: List[DataQualityCheck],
    failures: List[DataQualityFailure],
    warnings: List[DataQualityFailure],
) -> None:
    amount_keys = [key for key in ("TransAmount", "amount", "TransTotal") if key in raw]
    if len(amount_keys) < 2:
        _record_check(
            checks, failures, warnings,
            code="amount_alias_consistency",
            dimension="accuracy",
            passed=True,
            blocking=True,
            description="Supplied amount aliases must normalize to the same value",
            expression="normalize(amount_aliases) has exactly one distinct value",
            applicable=False,
        )
        return
    try:
        currency = normalize_currency(
            raw.get("Currency") or raw.get("CurrencyCode") or raw.get("currency") or "KES"
        )
        values = {
            normalize_amount(raw[key], currency=currency)
            for key in amount_keys
        }
        passed = len(values) == 1
    except (NormalizationError, TypeError, ValueError):
        passed = False
        values = set()
    _record_check(
        checks, failures, warnings,
        code="amount_alias_consistency",
        dimension="accuracy",
        passed=passed,
        blocking=True,
        description="Supplied amount aliases must normalize to the same value",
        expression="normalize(amount_aliases) has exactly one distinct value",
        value=len(values),
        reason="amount aliases disagree or one is invalid" if not passed else "",
    )


def _add_outlier_check(
    raw: Mapping[str, Any],
    normalized: Mapping[str, Any],
    reference_records: Sequence[Mapping[str, Any]],
    minimum_sample_size: int,
    threshold: Decimal,
    checks: List[DataQualityCheck],
    failures: List[DataQualityFailure],
    warnings: List[DataQualityFailure],
) -> None:
    applicable, outlier, sample_count, score = _is_amount_outlier(
        raw, normalized, reference_records, minimum_sample_size, threshold
    )
    _record_check(
        checks, failures, warnings,
        code="amount_outlier_mad",
        dimension="accuracy",
        passed=not outlier,
        blocking=False,
        description="Amount is within the robust peer-value outlier threshold",
        expression="0.6745 * abs(amount - median) / MAD <= configured threshold",
        value=round(score, 4) if score is not None else sample_count,
        reason=(
            f"amount is a robust statistical outlier against {sample_count} scoped peers"
            if outlier
            else ""
        ),
        applicable=applicable,
    )


def _is_amount_outlier(
    raw: Mapping[str, Any],
    normalized: Mapping[str, Any],
    reference_records: Sequence[Mapping[str, Any]],
    minimum_sample_size: int,
    threshold: Decimal,
) -> tuple[bool, bool, int, Optional[Decimal]]:
    if not reference_records:
        return False, False, 0, None
    try:
        currency = normalize_currency(
            raw.get("Currency") or raw.get("CurrencyCode") or raw.get("currency") or "KES"
        )
        amount = Decimal(normalize_amount(normalized.get("amount"), currency=currency))
    except (NormalizationError, InvalidOperation, TypeError, ValueError):
        return False, False, 0, None

    tenant_id = tenant_id_from_raw(dict(raw)).strip().casefold()
    provider_id = provider_id_from_raw(dict(raw)).strip().casefold()
    transaction_id = tx_id_from_raw(dict(raw)).strip().casefold()
    peers: List[Decimal] = []
    for peer in reference_records:
        if not isinstance(peer, Mapping):
            continue
        if (
            tenant_id_from_raw(dict(peer)).strip().casefold() != tenant_id
            or provider_id_from_raw(dict(peer)).strip().casefold() != provider_id
            or tx_id_from_raw(dict(peer)).strip().casefold() == transaction_id
        ):
            continue
        try:
            peer_currency = normalize_currency(
                peer.get("Currency") or peer.get("CurrencyCode") or peer.get("currency") or "KES"
            )
            if peer_currency != currency:
                continue
            peer_amount_raw = (
                peer.get("TransAmount")
                if "TransAmount" in peer
                else peer.get("amount")
            )
            peers.append(Decimal(normalize_amount(peer_amount_raw, currency=currency)))
        except (NormalizationError, InvalidOperation, TypeError, ValueError):
            continue
    if len(peers) < minimum_sample_size:
        return False, False, len(peers), None

    center = median(peers)
    deviations = [abs(peer - center) for peer in peers]
    mad = median(deviations)
    if mad:
        score = Decimal("0.6745") * abs(amount - center) / mad
        return True, score > threshold, len(peers), score

    ordered = sorted(peers)
    q1 = _quantile(ordered, Decimal("0.25"))
    q3 = _quantile(ordered, Decimal("0.75"))
    iqr = q3 - q1
    if iqr:
        lower = q1 - Decimal("1.5") * iqr
        upper = q3 + Decimal("1.5") * iqr
        return True, amount < lower or amount > upper, len(peers), None
    return True, amount != center, len(peers), None


def _quantile(values: Sequence[Decimal], fraction: Decimal) -> Decimal:
    position = Decimal(len(values) - 1) * fraction
    lower = int(position)
    upper = min(lower + 1, len(values) - 1)
    weight = position - lower
    return values[lower] * (Decimal(1) - weight) + values[upper] * weight


def _freshness_profile(
    raw: Mapping[str, Any],
    now: datetime,
    freshness_max_age_seconds: Optional[float],
) -> Dict[str, Any]:
    value = _timestamp_value(raw)
    if value is None:
        return {
            "status": "unknown",
            "age_seconds": None,
            "max_age_seconds": freshness_max_age_seconds,
        }
    try:
        if not isinstance(value, (str, datetime)):
            raise NormalizationError("timestamp must be text or datetime")
        text = (
            value.astimezone(timezone.utc).isoformat()
            if isinstance(value, datetime) and value.tzinfo is not None
            else value.isoformat()
            if isinstance(value, datetime)
            else value
        )
        parsed = datetime.fromisoformat(
            normalize_timestamp(text, default_timezone="UTC").replace("Z", "+00:00")
        )
    except (NormalizationError, TypeError, ValueError, OverflowError):
        return {
            "status": "invalid",
            "age_seconds": None,
            "max_age_seconds": freshness_max_age_seconds,
        }
    age = (now.astimezone(timezone.utc) - parsed).total_seconds()
    if age < -MAX_FUTURE_TIMESTAMP_SKEW_SECONDS:
        status = "future"
    elif freshness_max_age_seconds is not None and age > freshness_max_age_seconds:
        status = "stale"
    else:
        status = "fresh" if freshness_max_age_seconds is not None else "unbounded"
    return {
        "status": status,
        "age_seconds": round(age, 3),
        "max_age_seconds": freshness_max_age_seconds,
    }


def _duplicate_key(record: Mapping[str, Any]) -> Optional[tuple[str, str, str]]:
    transaction_id = tx_id_from_raw(dict(record)).strip().upper()
    tenant_id = tenant_id_from_raw(dict(record)).strip().upper()
    provider_id = provider_id_from_raw(dict(record)).strip().upper()
    if not transaction_id or not tenant_id or not provider_id:
        return None
    return tenant_id, provider_id, transaction_id


def _add_duplicate_result(result: DataQualityResult, *, identical: bool) -> None:
    if identical:
        _record_check(
            result.checks, result.failures, result.warnings,
            code="duplicate_transaction",
            dimension="uniqueness",
            passed=False,
            blocking=False,
            description="Transaction identifier is unique within tenant/provider scope",
            expression="count(tenant_id, provider_id, transaction_id) = 1",
            reason="identical transaction record occurs more than once in this batch",
        )
        return
    _record_check(
        result.checks, result.failures, result.warnings,
        code="duplicate_transaction_conflict",
        dimension="uniqueness",
        passed=False,
        blocking=True,
        description="Repeated transaction identifiers must have identical payloads",
        expression="same scoped transaction ID implies identical payload hash",
        reason="transaction identifier is reused with a conflicting payload",
    )
    result.status = DataQualityStatus.FAIL


def _json_hash(payload: Dict[str, Any]) -> str:
    serialized = json.dumps(payload, sort_keys=True, default=str, separators=(",", ":"))
    return hashlib.sha256(serialized.encode("utf-8")).hexdigest()


def _profile_payload(payload: Dict[str, Any]) -> Dict[str, Any]:
    if not isinstance(payload, dict):
        return {"type": "non_object"}
    keys = list(payload.keys())
    non_empty = [k for k in keys if payload[k] not in (None, "", [], {})]
    return {
        "type": "object",
        "key_count": len(keys),
        "non_empty_key_count": len(non_empty),
        "null_fields": [key for key in keys if payload[key] is None],
        "empty_fields": [
            key
            for key in keys
            if payload[key] is None or payload[key] == "" or payload[key] == [] or payload[key] == {}
        ],
        "keys": keys,
    }


def _normalize_payload(raw: Dict[str, Any]) -> Dict[str, Any]:
    return {
        "transaction_id": raw.get("TransID") or raw.get("TransactionID") or raw.get("transaction_id"),
        "tenant_id": raw.get("TenantID") or raw.get("tenant_id") or raw.get("tenantId"),
        "provider_id": raw.get("Provider") or raw.get("provider") or raw.get("provider_id"),
        "amount": raw.get("TransAmount") or raw.get("amount"),
        "currency": raw.get("Currency") or raw.get("currency") or raw.get("TransCurrency"),
        "timestamp": raw.get("TransTime") or raw.get("transaction_time"),
        "reference": raw.get("BillRefNumber") or raw.get("Reference") or raw.get("reference"),
    }


def _now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()
