"""Phase 9 provenance, data-quality, and quarantine orchestration.

Owned responsibilities:
* Validate incoming provider payloads against the data-quality rule set.
* Build a complete lineage snapshot (provider -> webhook -> raw -> normalized ->
  fraud -> reconciliation -> exception -> report) for each transaction.
* Persist TransactionProvenance as the exit-gate anchor for each transaction.
* Route failing records to QuarantineRecord instead of contaminating production
  transaction datasets.
* Track schema/pipeline versions so every decision is traceable to the version
  that produced it.
"""
from __future__ import annotations

import hashlib
import json
import logging
import math
import os
from datetime import datetime, timezone
from typing import Any, Dict, Optional

from data_lineage import (
    PIPELINE_VERSION_KEY,
    SCHEMA_VERSION_KEY,
    build_lineage,
    lineage_schema_version,
)
from data_profiling import profile_payload
from data_quality import (
    DataQualityFailure,
    DataQualityResult,
    DataQualityStatus,
    run_data_quality,
)
from dq_rules import (
    _rule_amount_positive,
    _rule_currency_valid,
    _rule_provider_valid,
    _rule_reference_valid,
    _rule_schema_version_present,
    _rule_tenant_exists,
    _rule_transaction_id_not_null,
)
from data_protection import protect_payload
from models import QuarantineRecord, QuarantineRemediation, TransactionProvenance
from schema_evolution import (
    assess_schema_compatibility,
    schema_version_from_payload,
)

logger = logging.getLogger(__name__)

DEFAULT_SCHEMA_VERSION = os.getenv("DATA_SCHEMA_VERSION", "1.0")
DEFAULT_PIPELINE_VERSION = os.getenv("DATA_PIPELINE_VERSION", "1.0")
DATETIME_FACTORY = datetime.now


def _iso_now() -> str:
    return DATETIME_FACTORY(timezone.utc).isoformat()


def _sha256_json(payload: Dict[str, Any]) -> str:
    serialized = json.dumps(
        payload, sort_keys=True, default=str, separators=(",", ":")
    )
    return hashlib.sha256(serialized.encode("utf-8")).hexdigest()


def _safe_json(value: Any) -> Dict[str, Any]:
    if isinstance(value, dict):
        return value
    if isinstance(value, list):
        return {"__list__": value}
    return {"__raw__": str(value)[:4096]}


class ProvenanceError(Exception):
    """Raised when provenance recording cannot be completed safely."""


_REMEDIATION_TRANSITIONS = {
    "review_started": {"open": "reviewing"},
    "correction_recorded": {"open": "corrected", "reviewing": "corrected"},
    "replay_requested": {"corrected": "replay_pending"},
    "replay_completed": {"replay_pending": "resolved"},
    "resolved": {
        "open": "resolved",
        "reviewing": "resolved",
        "corrected": "resolved",
        "replay_pending": "resolved",
    },
    "false_positive": {
        "open": "false_positive",
        "reviewing": "false_positive",
        "corrected": "false_positive",
    },
}
_TERMINAL_REMEDIATION_STATES = frozenset({"resolved", "false_positive"})


def _freshness_limit(value: Optional[float]) -> Optional[float]:
    if value is None:
        configured = os.getenv("PESAGUARD_DQ_MAX_EVENT_AGE_SECONDS", "").strip()
        if not configured:
            return None
        value = configured
    try:
        seconds = float(value)
    except (TypeError, ValueError) as exc:
        raise ValueError("PESAGUARD_DQ_MAX_EVENT_AGE_SECONDS must be a positive number") from exc
    if not math.isfinite(seconds) or seconds <= 0:
        raise ValueError("PESAGUARD_DQ_MAX_EVENT_AGE_SECONDS must be a positive finite number")
    return seconds


def record_quarantine_remediation(
    session,
    *,
    tenant_id: str,
    quarantine_id: str,
    actor_id: str,
    action: str,
    corrected_payload: Optional[Dict[str, Any]] = None,
) -> QuarantineRemediation:
    """Append one audited remediation transition for a tenant-owned quarantine."""
    tenant = tenant_id.strip() if isinstance(tenant_id, str) else ""
    actor = actor_id.strip() if isinstance(actor_id, str) else ""
    if not tenant or len(tenant) > 128:
        raise ValueError("tenant_id must be a non-empty string of at most 128 characters")
    if not actor or len(actor) > 128:
        raise ValueError("actor_id must be a non-empty string of at most 128 characters")
    if not isinstance(quarantine_id, str) or not quarantine_id.strip():
        raise ValueError("quarantine_id is required")
    if action == "correction_recorded" and not isinstance(corrected_payload, dict):
        raise ValueError("correction_recorded requires a corrected payload")
    if corrected_payload is not None and action != "correction_recorded":
        raise ValueError("corrected_payload is only allowed for correction_recorded")

    quarantine = (
        session.query(QuarantineRecord)
        .filter_by(id=quarantine_id, tenant_id=tenant)
        .with_for_update()
        .one_or_none()
    )
    if quarantine is None:
        raise LookupError("quarantine record not found")
    if action not in _REMEDIATION_TRANSITIONS:
        raise ValueError(f"unsupported remediation action: {action}")

    latest = (
        session.query(QuarantineRemediation)
        .filter_by(tenant_id=tenant, quarantine_id=quarantine_id)
        .order_by(QuarantineRemediation.created_at.desc(), QuarantineRemediation.id.desc())
        .first()
    )
    current_status = latest.to_status if latest else "open"
    if current_status in _TERMINAL_REMEDIATION_STATES:
        raise ValueError("quarantine remediation is already in a terminal state")
    next_status = _REMEDIATION_TRANSITIONS[action].get(current_status)
    if next_status is None:
        raise ValueError(
            f"invalid remediation transition: {current_status} -> {action}"
        )

    evidence_hash = (
        _sha256_json(corrected_payload)
        if corrected_payload is not None
        else None
    )
    record = QuarantineRemediation(
        tenant_id=tenant,
        quarantine_id=quarantine_id,
        action=action,
        from_status=current_status,
        to_status=next_status,
        actor_id=actor,
        evidence_sha256=evidence_hash,
    )
    session.add(record)
    session.flush()
    return record


def list_quarantine_remediations(
    session,
    *,
    tenant_id: str,
    quarantine_id: str,
) -> list[QuarantineRemediation]:
    """Return remediation history only after verifying tenant ownership."""
    if not isinstance(tenant_id, str) or not tenant_id.strip():
        raise ValueError("tenant_id is required")
    quarantine = (
        session.query(QuarantineRecord)
        .filter_by(id=quarantine_id, tenant_id=tenant_id.strip())
        .one_or_none()
    )
    if quarantine is None:
        raise LookupError("quarantine record not found")
    return (
        session.query(QuarantineRemediation)
        .filter_by(tenant_id=tenant_id.strip(), quarantine_id=quarantine_id)
        .order_by(QuarantineRemediation.created_at, QuarantineRemediation.id)
        .all()
    )


def _pick(raw: Dict[str, Any], *keys: str) -> Optional[str]:
    for key in keys:
        if key in raw:
            value = raw[key]
            if isinstance(value, str):
                return value.strip() or None
            if isinstance(value, (int, float)):
                return str(value)
    return None


def _provider_from_payload(raw: Dict[str, Any]) -> Optional[str]:
    provider = _pick(raw, "Provider", "provider", "source", "provider_id")
    if provider:
        return provider.strip().lower()
    return None


def _tenant_id_from_payload(raw: Dict[str, Any]) -> Optional[str]:
    return _pick(raw, "TenantID", "tenant_id", "tenantId", "Tenant-Id")


def _transaction_id_from_payload(raw: Dict[str, Any]) -> Optional[str]:
    return _pick(raw, "TransID", "TransactionID", "transaction_id")


def _normalize_payload(raw: Dict[str, Any]) -> Dict[str, Any]:
    normalized = {
        "transaction_id": raw.get("TransID") or raw.get("TransactionID") or raw.get("transaction_id"),
        "tenant_id": raw.get("TenantID") or raw.get("tenant_id") or raw.get("tenantId"),
        "provider_id": raw.get("Provider") or raw.get("provider") or raw.get("provider_id"),
        "amount": raw.get("TransAmount") or raw.get("amount"),
        "currency": raw.get("Currency") or raw.get("currency") or raw.get("TransCurrency"),
        "timestamp": raw.get("TransTime") or raw.get("transaction_time"),
        "reference": raw.get("BillRefNumber") or raw.get("Reference") or raw.get("reference"),
    }
    normalized.update({k: v for k, v in raw.items() if k not in normalized})
    return normalized


def _provenance_row_from_quality(
    result: DataQualityResult,
    raw: Dict[str, Any],
    *,
    schema_version: Optional[str] = None,
    pipeline_version: Optional[str] = None,
) -> Dict[str, Any]:
    provider_id = result.provider_id or _provider_from_payload(raw) or "unknown"
    tx_id = result.transaction_id or _transaction_id_from_payload(raw) or "unknown"
    tenant_id = result.tenant_id or _tenant_id_from_payload(raw) or "unknown"
    schema_version = schema_version or schema_version_from_payload(raw) or DEFAULT_SCHEMA_VERSION
    pipeline_version = pipeline_version or DEFAULT_PIPELINE_VERSION
    normalized_snapshot = _safe_json(_normalize_payload(raw))
    lineage = build_lineage(
        event_id=str(raw.get("event_id") or tx_id),
        transaction_id=tx_id,
        tenant_id=tenant_id,
        provider_id=provider_id,
    )
    lineage = lineage.to_dict()
    lineage["processed_at"] = _iso_now()
    return {
        "transaction_id": tx_id,
        "tenant_id": tenant_id,
        "provider_id": provider_id,
        "lineage_id": lineage["lineage_id"],
        "event_id": lineage["event_id"],
        "schema_version": schema_version,
        "pipeline_version": pipeline_version,
        "dq_status": result.status,
        "dq_overall_score": result.overall_score,
        "dq_completeness": result.completeness,
        "dq_validity": result.validity,
        "dq_timeliness": result.timeliness,
        "dq_accuracy": result.accuracy,
        "dq_consistency": result.consistency,
        "dq_uniqueness": result.uniqueness,
        "dq_failures": result.to_dict()["failures"],
        "dq_warnings": result.to_dict()["warnings"],
        "dq_checks": [check.to_dict() for check in result.checks],
        "raw_hash": _sha256_json(raw),
        "normalized_snapshot": normalized_snapshot,
        "lineage_snapshot": lineage,
    }



def persist_provenance(
    session,
    result: DataQualityResult,
    raw: Dict[str, Any],
    *,
    schema_version: Optional[str] = None,
    pipeline_version: Optional[str] = None,
) -> TransactionProvenance:
    row = _provenance_row_from_quality(
        result, raw, schema_version=schema_version, pipeline_version=pipeline_version
    )
    record = TransactionProvenance(
        lineage_id=row["lineage_id"],
        event_id=row["event_id"],
        transaction_id=row["transaction_id"],
        tenant_id=row["tenant_id"],
        provider_id=row["provider_id"],
        raw_payload_sha256=row["raw_hash"],
        schema_version=row["schema_version"],
        pipeline_version=row["pipeline_version"],
        dq_status=row["dq_status"],
        dq_overall_score=row["dq_overall_score"],
        dq_completeness=row["dq_completeness"],
        dq_validity=row["dq_validity"],
        dq_timeliness=row["dq_timeliness"],
        dq_accuracy=row["dq_accuracy"],
        dq_consistency=row["dq_consistency"],
        dq_uniqueness=row["dq_uniqueness"],
        dq_failures=row["dq_failures"],
        dq_warnings=row["dq_warnings"],
        dq_checks=row["dq_checks"],
        lineage_snapshot=row.get("lineage_snapshot"),
        dq_checked_at=datetime.now(timezone.utc),
        normalized_payload_sha256=_sha256_json(row["normalized_snapshot"]),
    )
    session.add(record)
    session.flush()
    return record



def quarantine_record(
    session,
    result: DataQualityResult,
    raw: Dict[str, Any],
    *,
    schema_version: Optional[str] = None,
    pipeline_version: Optional[str] = None,
    quarantine_reason: Optional[str] = None,
) -> QuarantineRecord:
    row = _provenance_row_from_quality(
        result, raw, schema_version=schema_version, pipeline_version=pipeline_version
    )
    failure_reasons = "; ".join(sorted({f.reason for f in result.failures}))
    if quarantine_reason:
        failure_reasons = f"{quarantine_reason}; {failure_reasons}".strip("; ")
    existing = (
        session.query(QuarantineRecord)
        .filter_by(
            tenant_id=row["tenant_id"],
            provider_id=row["provider_id"],
            deduplication_key=row["raw_hash"],
        )
        .one_or_none()
    )
    if existing is not None:
        return existing
    record = QuarantineRecord(
        transaction_id=row["transaction_id"],
        tenant_id=row["tenant_id"],
        provider_id=row["provider_id"],
        lineage_id=row["lineage_id"],
        reason=failure_reasons or "quality_gate_failure",
        failure_rule=", ".join(sorted({failure.rule.code for failure in result.failures})) or None,
        payload=protect_payload(_safe_json(raw)),
        payload_sha256=row["raw_hash"],
        deduplication_key=row["raw_hash"],
        rejection_context={
            "dq_status": row["dq_status"],
            "dimension_scores": result.dimension_scores,
            "failures": row["dq_failures"],
            "warnings": row["dq_warnings"],
            "checks": row["dq_checks"],
            "overall_score": row["dq_overall_score"],
            "scoring_version": "equal_dimension_rule_mean_v1",
            "schema_version": row["schema_version"],
            "pipeline_version": row["pipeline_version"],
            "event_id": row["event_id"],
        },
    )
    session.add(record)
    session.flush()
    return record



class DataProvenance:
    """Orchestrates validation, lineage, persistence, and quarantine for one payload.

    This is the Phase 9 exit gate for incoming provider data. Every important
    transaction should be able to answer:

    * Where did this data originate?
    * What changed it?
    * Which service processed it?
    * Which version processed it?
    * What was the final decision?
    """

    def __init__(
        self,
        *,
        schema_version: Optional[str] = None,
        pipeline_version: Optional[str] = None,
        lineage_builder=None,
        now_fn=None,
        freshness_max_age_seconds: Optional[float] = None,
        reference_records=None,
    ):
        self.schema_version = schema_version or DEFAULT_SCHEMA_VERSION
        self.pipeline_version = pipeline_version or DEFAULT_PIPELINE_VERSION
        self.lineage_builder = lineage_builder or build_lineage
        self.now_fn = now_fn or _iso_now
        self.freshness_max_age_seconds = _freshness_limit(freshness_max_age_seconds)
        self.reference_records = reference_records or ()

    def evaluate(self, raw: Dict[str, Any]) -> DataQualityResult:
        lineage = self.lineage_builder(
            event_id=str(raw.get("event_id") or _transaction_id_from_payload(raw) or "unknown"),
            transaction_id=_transaction_id_from_payload(raw) or "unknown",
            tenant_id=_tenant_id_from_payload(raw) or "unknown",
            provider_id=_provider_from_payload(raw),
        )
        return run_data_quality(
            raw,
            lineage=lineage,
            schema_version=schema_version_from_payload(raw),
            pipeline_version=self.pipeline_version,
            expected_schema_version=self.schema_version,
            freshness_max_age_seconds=self.freshness_max_age_seconds,
            reference_records=self.reference_records,
        )

    def process(
        self,
        raw: Dict[str, Any],
        session,
        *,
        quarantine_on_failure: bool = True,
    ) -> Dict[str, Any]:
        quality = self.evaluate(raw)
        from metrics import record_data_quality
        record_data_quality(quality)
        if quality.status == DataQualityStatus.FAIL:
            if quarantine_on_failure:
                quarantine_record(
                    session,
                    quality,
                    raw,
                    schema_version=self.schema_version,
                    pipeline_version=self.pipeline_version,
                )
            return {
                "decision": "quarantined",
                "transaction_id": quality.transaction_id,
                "tenant_id": quality.tenant_id,
                "provider_id": quality.provider_id,
                "quality": quality.to_dict(),
            }
        schema_compat, _note = assess_schema_compatibility(
            self.schema_version, schema_version_from_payload(raw) or DEFAULT_SCHEMA_VERSION
        )
        if schema_compat == "incompatible":
            quarantine_record(
                session,
                quality,
                raw,
                schema_version=self.schema_version,
                pipeline_version=self.pipeline_version,
                quarantine_reason="schema_incompatible",
            )
            return {
                "decision": "quarantined",
                "transaction_id": quality.transaction_id,
                "tenant_id": quality.tenant_id,
                "provider_id": quality.provider_id,
                "quality": quality.to_dict(),
            }
        persist_provenance(
            session,
            quality,
            raw,
            schema_version=self.schema_version,
            pipeline_version=self.pipeline_version,
        )
        return {
            "decision": "accepted",
            "transaction_id": quality.transaction_id,
            "tenant_id": quality.tenant_id,
            "provider_id": quality.provider_id,
            "quality": quality.to_dict(),
        }


def record_webhook_provenance(
    session,
    raw: Dict[str, Any],
    *,
    schema_version: Optional[str] = None,
    pipeline_version: Optional[str] = None,
) -> Dict[str, Any]:
    """Convenience wrapper used by the webhook ingestion path.

    Returns the same decision dict as ``DataProvenance.process`` so callers can
    branch on ``decision`` without constructing a full orchestrator instance.
    """
    orchestrator = DataProvenance(
        schema_version=schema_version,
        pipeline_version=pipeline_version,
    )
    return orchestrator.process(raw, session, quarantine_on_failure=True)
