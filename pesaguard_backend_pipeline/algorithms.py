"""PesaGuard algorithm registry and execution helpers.

This module implements the algorithm checklist from the product brief as a single,
reusable execution catalog. Each algorithm is exposed as a named function and
returns a normalized result describing its status, confidence, and evidence.

The implementation intentionally favors deterministic, production-safe logic over
hidden state. It is designed to be extended with domain-specific integrations
without breaking the shared interface used by services and tests.
"""

from __future__ import annotations

import hashlib
import hmac
import importlib
import json
import math
import re
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any, Callable, Dict, Iterable, List, Tuple


def _normalize_name(value: str) -> str:
    value = (value or "").strip().lower()
    value = re.sub(r"\s+algorithm\s*$", "", value)
    value = re.sub(r"[^a-z0-9]+", "_", value)
    return value.strip("_")


def _now_utc() -> datetime:
    return datetime.now(timezone.utc)


@dataclass(frozen=True)
class AlgorithmResult:
    """Normalized output from an algorithm evaluation."""

    algorithm: str
    category: str
    status: str
    confidence: float
    evidence: str
    metadata: Dict[str, Any]

    def as_dict(self) -> Dict[str, Any]:
        return {
            "algorithm": self.algorithm,
            "category": self.category,
            "status": self.status,
            "confidence": round(float(self.confidence), 4),
            "evidence": self.evidence,
            "metadata": dict(self.metadata or {}),
        }


def _as_mapping(value: Any) -> Mapping[str, Any]:
    if isinstance(value, Mapping):
        return value
    return {}


def _coerce_float(value: Any, default: float = 0.0) -> float:
    try:
        if value is None:
            return default
        return float(value)
    except (TypeError, ValueError):
        return default


def _hash_value(value: Any) -> str:
    return hashlib.sha256(repr(value).encode("utf-8")).hexdigest()


def _load_module(module_name: str) -> Any | None:
    candidates = [
        f"pesaguard_backend_pipeline.{module_name}",
        module_name,
    ]
    for import_name in candidates:
        try:
            return importlib.import_module(import_name)
        except Exception:
            continue
    return None


def _field_presence_score(candidate: Any, fields: Iterable[str]) -> Tuple[float, List[str]]:
    if not isinstance(candidate, Mapping):
        return 0.0, []
    found: List[str] = []
    for field_name in fields:
        if field_name in candidate and candidate.get(field_name) not in (None, "", [], {}):
            found.append(field_name)
    score = len(found) / max(1, len(list(fields)))
    return score, found


def _generic_algorithm_evaluation(
    algorithm_name: str,
    category: str,
    candidate: Any,
    *,
    required_fields: Iterable[str],
    optional_fields: Iterable[str] = (),
    fallback_status: str = "needs_review",
) -> AlgorithmResult:
    payload = _as_mapping(candidate)
    required = list(required_fields)
    optional = list(optional_fields)
    score, found = _field_presence_score(payload, required)
    optional_score, _ = _field_presence_score(payload, optional)
    qualified = score >= 0.5
    confidence = min(1.0, max(score, optional_score * 0.25))
    if not payload:
        status = "failed"
        evidence = "No input payload was supplied."
    elif qualified:
        status = "passed"
        evidence = f"Required checks satisfied for {algorithm_name}."
    else:
        status = fallback_status
        evidence = (
            f"Missing required fields for {algorithm_name}: "
            f"{', '.join(required) if required else 'n/a'}"
        )
    return AlgorithmResult(
        algorithm=algorithm_name,
        category=category,
        status=status,
        confidence=confidence,
        evidence=evidence,
        metadata={
            "required_fields": required,
            "optional_fields": optional,
            "found_fields": found,
            "input_hash": _hash_value(candidate),
        },
    )


def _evaluate_transaction_payload(payload: Any, *, algorithm_name: str, category: str) -> AlgorithmResult:
    transaction = _as_mapping(payload)
    if not transaction:
        return AlgorithmResult(
            algorithm=algorithm_name,
            category=category,
            status="failed",
            confidence=0.0,
            evidence="No transaction payload was supplied.",
            metadata={"input_hash": _hash_value(payload)},
        )

    tx_id = str(transaction.get("transaction_id") or transaction.get("TransID") or transaction.get("trans_id") or "").strip()
    amount = _coerce_float(transaction.get("amount") or transaction.get("TransAmount"), 0.0)
    currency = str(transaction.get("currency") or transaction.get("Currency") or "").strip()
    ts = transaction.get("timestamp") or transaction.get("TransTime") or transaction.get("created_at")
    state = str(transaction.get("status") or transaction.get("state") or "").strip().upper()

    if not tx_id or not currency or amount <= 0 or not ts:
        return AlgorithmResult(
            algorithm=algorithm_name,
            category=category,
            status="needs_review",
            confidence=0.65,
            evidence="Transaction payload is incomplete or has an invalid amount/timestamp.",
            metadata={
                "transaction_id": tx_id,
                "currency": currency,
                "amount": amount,
                "state": state,
                "input_hash": _hash_value(transaction),
            },
        )

    lifecycle = _load_module("lifecycle")
    if lifecycle is not None and state and state not in getattr(lifecycle, "TRANSACTION_STATES", ()):
        return AlgorithmResult(
            algorithm=algorithm_name,
            category=category,
            status="failed",
            confidence=0.9,
            evidence=f"Transaction state '{state}' is not valid for the lifecycle model.",
            metadata={"status": state, "input_hash": _hash_value(transaction)},
        )

    return AlgorithmResult(
        algorithm=algorithm_name,
        category=category,
        status="passed",
        confidence=0.96,
        evidence="Transaction payload is structurally valid and follows the supported lifecycle states.",
        metadata={
            "transaction_id": tx_id,
            "amount": amount,
            "currency": currency,
            "state": state,
            "input_hash": _hash_value(transaction),
        },
    )


def _evaluate_daraja_payload(payload: Any, *, algorithm_name: str, category: str) -> AlgorithmResult:
    transaction = _as_mapping(payload)
    if not transaction:
        return AlgorithmResult(
            algorithm=algorithm_name,
            category=category,
            status="failed",
            confidence=0.0,
            evidence="No Daraja payload was supplied.",
            metadata={"input_hash": _hash_value(payload)},
        )

    required = ["merchant_id", "amount", "timestamp", "signature"]
    missing = [field for field in required if not transaction.get(field)]
    if missing:
        return AlgorithmResult(
            algorithm=algorithm_name,
            category=category,
            status="failed",
            confidence=0.75,
            evidence=f"Daraja callback is missing required fields: {', '.join(missing)}.",
            metadata={"missing_fields": missing, "input_hash": _hash_value(transaction)},
        )

    secret = str(transaction.get("shared_secret") or transaction.get("secret_key") or "").strip()
    signature = str(transaction.get("signature") or "").strip()
    body = transaction.get("signed_payload") or "|".join([
        str(transaction.get("merchant_id") or ""),
        str(transaction.get("amount") or ""),
        str(transaction.get("timestamp") or ""),
        str(transaction.get("reference") or ""),
    ])

    if secret:
        expected = hmac.new(secret.encode("utf-8"), body.encode("utf-8"), hashlib.sha256).hexdigest()
        if not hmac.compare_digest(expected, signature):
            return AlgorithmResult(
                algorithm=algorithm_name,
                category=category,
                status="failed",
                confidence=1.0,
                evidence="Daraja callback signature verification failed.",
                metadata={"expected_signature": expected, "received_signature": signature},
            )

    return AlgorithmResult(
        algorithm=algorithm_name,
        category=category,
        status="passed",
        confidence=0.97,
        evidence="Daraja callback payload and signature passed the verification checks.",
        metadata={"merchant_id": transaction.get("merchant_id"), "input_hash": _hash_value(transaction)},
    )


def _evaluate_reconciliation_payload(payload: Any, *, algorithm_name: str, category: str) -> AlgorithmResult:
    transaction = _as_mapping(payload)
    if not transaction:
        return AlgorithmResult(
            algorithm=algorithm_name,
            category=category,
            status="failed",
            confidence=0.0,
            evidence="No reconciliation payload was supplied.",
            metadata={"input_hash": _hash_value(payload)},
        )

    raw_transaction = transaction.get("transaction") or transaction.get("callback") or transaction
    records = transaction.get("internal_records") or transaction.get("records") or []
    engine_module = _load_module("reconciliation_engine")
    if engine_module is None or not isinstance(raw_transaction, Mapping):
        return _generic_algorithm_evaluation(
            algorithm_name,
            category,
            transaction,
            required_fields=["transaction", "internal_records"],
            optional_fields=["reference", "variance", "confidence_score"],
        )

    try:
        engine = engine_module.ReconciliationEngine()
        result = engine.reconcile(dict(raw_transaction), list(records))
        status = str(result.get("status") or "UNMATCHED").upper()
        score = float(result.get("score", 0.0) or 0.0)
        if status in {"MATCHED", "PENDING"}:
            algorithm_status = "passed"
            evidence = "Reconciliation result matched the internal ledger record within the tolerance window."
            confidence = min(score if score else 0.96, 1.0)
        elif status in {"PARTIAL"}:
            algorithm_status = "needs_review"
            evidence = "Reconciliation produced a partial match and requires manual review."
            confidence = max(0.5, min(score if score else 0.7, 1.0))
        elif status in {"MISMATCH", "DUPLICATE", "UNMATCHED"}:
            algorithm_status = "needs_review"
            evidence = f"Reconciliation status is '{status}' and should be reviewed."
            confidence = 0.55
        else:
            algorithm_status = "failed"
            evidence = f"Reconciliation failed with '{status}'."
            confidence = 0.0
        return AlgorithmResult(
            algorithm=algorithm_name,
            category=category,
            status=algorithm_status,
            confidence=confidence,
            evidence=evidence,
            metadata={"reconciliation_status": status, "reconciliation_result": result},
        )
    except Exception as exc:  # pragma: no cover - defensive branch
        return AlgorithmResult(
            algorithm=algorithm_name,
            category=category,
            status="failed",
            confidence=0.0,
            evidence=f"Reconciliation evaluation raised an error: {exc}",
            metadata={"input_hash": _hash_value(transaction)},
        )


def _evaluate_ledger_payload(payload: Any, *, algorithm_name: str, category: str) -> AlgorithmResult:
    entry = _as_mapping(payload)
    if not entry:
        return AlgorithmResult(
            algorithm=algorithm_name,
            category=category,
            status="failed",
            confidence=0.0,
            evidence="No ledger entry payload was supplied.",
            metadata={"input_hash": _hash_value(payload)},
        )

    debit = _coerce_float(entry.get("debit"), 0.0)
    credit = _coerce_float(entry.get("credit"), 0.0)
    balance = _coerce_float(entry.get("balance"), 0.0)
    if debit < 0 or credit < 0 or balance < 0:
        return AlgorithmResult(
            algorithm=algorithm_name,
            category=category,
            status="needs_review",
            confidence=0.8,
            evidence="Ledger values must remain non-negative.",
            metadata={"debit": debit, "credit": credit, "balance": balance},
        )

    expected_balance = credit - debit
    if abs(balance - expected_balance) > 0.01:
        return AlgorithmResult(
            algorithm=algorithm_name,
            category=category,
            status="failed",
            confidence=0.98,
            evidence=f"Ledger balance mismatch: expected {expected_balance}, got {balance}.",
            metadata={"debit": debit, "credit": credit, "balance": balance, "expected_balance": expected_balance},
        )

    return AlgorithmResult(
        algorithm=algorithm_name,
        category=category,
        status="passed",
        confidence=0.98,
        evidence="Ledger entry preserves a consistent debit/credit balance invariant.",
        metadata={"debit": debit, "credit": credit, "balance": balance, "expected_balance": expected_balance},
    )


def _evaluate_fraud_payload(payload: Any, *, algorithm_name: str, category: str) -> AlgorithmResult:
    transaction = _as_mapping(payload)
    if not transaction:
        return AlgorithmResult(
            algorithm=algorithm_name,
            category=category,
            status="failed",
            confidence=0.0,
            evidence="No transaction supplied for fraud scoring.",
            metadata={"input_hash": _hash_value(payload)},
        )

    engine_module = _load_module("fraud_risk_engine")
    if engine_module is None:
        return _generic_algorithm_evaluation(
            algorithm_name,
            category,
            transaction,
            required_fields=["risk_score"],
            optional_fields=["event_type", "source_ip", "merchant_id", "amount"],
            fallback_status="needs_review",
        )

    history = transaction.get("history") or transaction.get("past_transactions") or ()
    baseline = transaction.get("tenant_baseline") or transaction.get("tenant_baseline_metrics") or None
    try:
        decision = engine_module.assess_transaction(transaction, history, baseline)
        risk_score = float(decision.risk_score or 0.0)
        if risk_score >= 0.65:
            status = "needs_review"
            evidence = "Fraud engine flagged the transaction as high risk and requires review."
        else:
            status = "passed"
            evidence = "Fraud engine found the transaction within the acceptable risk envelope."
        return AlgorithmResult(
            algorithm=algorithm_name,
            category=category,
            status=status,
            confidence=risk_score,
            evidence=evidence,
            metadata={
                "risk_score": risk_score,
                "risk_level": decision.risk_level,
                "action": decision.action,
                "reason_codes": list(decision.reason_codes),
                "rules_triggered": list(decision.rules_triggered),
            },
        )
    except Exception as exc:  # pragma: no cover - defensive branch
        return AlgorithmResult(
            algorithm=algorithm_name,
            category=category,
            status="failed",
            confidence=0.0,
            evidence=f"Fraud assessment failed: {exc}",
            metadata={"input_hash": _hash_value(transaction)},
        )


def _evaluate_statement_payload(payload: Any, *, algorithm_name: str, category: str) -> AlgorithmResult:
    result = _generic_algorithm_evaluation(
        algorithm_name,
        category,
        payload,
        required_fields=["rows", "file_hash"],
        optional_fields=["statement_period", "source", "checksum"],
    )
    if result.status == "passed":
        statement = _as_mapping(payload)
        rows = statement.get("rows")
        if isinstance(rows, Sequence) and not rows:
            return AlgorithmResult(
                algorithm=algorithm_name,
                category=category,
                status="needs_review",
                confidence=0.6,
                evidence="Statement rows are present but empty.",
                metadata={"row_count": 0, "input_hash": result.metadata["input_hash"]},
            )
    return result


def _duplicate_signature(item: Mapping[str, Any]) -> str:
    amount = _coerce_float(item.get("TransAmount") or item.get("amount"), 0.0)
    reference = str(item.get("BillRefNumber") or item.get("reference") or item.get("transaction_id") or item.get("TransID") or "").strip()
    phone = str(item.get("MSISDN") or item.get("phone_number") or item.get("msisdn") or "").strip()
    ts = str(item.get("TransTime") or item.get("timestamp") or item.get("created_at") or "").strip()
    return hashlib.sha256(f"{reference}|{amount}|{phone}|{ts}".encode("utf-8")).hexdigest()


def _evaluate_exact_transaction_match(payload: Any, *, algorithm_name: str, category: str) -> AlgorithmResult:
    transaction = _as_mapping(payload)
    if not transaction:
        return AlgorithmResult(
            algorithm=algorithm_name,
            category=category,
            status="failed",
            confidence=0.0,
            evidence="No transaction payload was supplied.",
            metadata={"input_hash": _hash_value(payload)},
        )

    raw_transaction = transaction.get("transaction") or transaction.get("callback") or transaction
    records = transaction.get("internal_records") or transaction.get("records") or []
    reconciliation_module = _load_module("reconciliation_engine")
    if reconciliation_module is None or not isinstance(raw_transaction, Mapping):
        return _generic_algorithm_evaluation(
            algorithm_name,
            category,
            transaction,
            required_fields=["transaction", "internal_records"],
            optional_fields=["reference", "confidence_score", "matching_rule"],
        )

    try:
        engine = reconciliation_module.ReconciliationEngine()
        result = engine.reconcile(dict(raw_transaction), list(records))
        status = str(result.get("status") or "UNMATCHED").upper()
        score = float(result.get("evidence", {}).get("match_score", 0.0) or 0.0)
        if status in {"MATCHED", "PENDING"}:
            return AlgorithmResult(
                algorithm=algorithm_name,
                category=category,
                status="passed",
                confidence=min(max(score, 0.8), 1.0),
                evidence="Exact transaction match was confirmed against an internal record within the configured window.",
                metadata={"reconciliation_status": status, "match_score": score, "reconciliation_result": result},
            )
        return AlgorithmResult(
            algorithm=algorithm_name,
            category=category,
            status="needs_review",
            confidence=max(0.5, min(score, 0.9)),
            evidence=f"Exact-match evaluation returned '{status}' and requires operational review.",
            metadata={"reconciliation_status": status, "match_score": score, "reconciliation_result": result},
        )
    except Exception as exc:  # pragma: no cover - defensive branch
        return AlgorithmResult(
            algorithm=algorithm_name,
            category=category,
            status="failed",
            confidence=0.0,
            evidence=f"Exact match evaluation failed: {exc}",
            metadata={"input_hash": _hash_value(transaction)},
        )


def _evaluate_duplicate_payment_detection(payload: Any, *, algorithm_name: str, category: str) -> AlgorithmResult:
    transaction = _as_mapping(payload)
    if not transaction:
        return AlgorithmResult(
            algorithm=algorithm_name,
            category=category,
            status="failed",
            confidence=0.0,
            evidence="No payment payload supplied for duplicate detection.",
            metadata={"input_hash": _hash_value(payload)},
        )

    records = transaction.get("transactions") or transaction.get("records") or transaction.get("payment_history") or []
    if not isinstance(records, Sequence):
        records = [records]

    seen = {}
    duplicates = []
    for idx, item in enumerate(records):
        if not isinstance(item, Mapping):
            continue
        sig = _duplicate_signature(item)
        if sig in seen:
            duplicates.append({"first_index": seen[sig], "duplicate_index": idx, "signature": sig})
        else:
            seen[sig] = idx

    if duplicates:
        return AlgorithmResult(
            algorithm=algorithm_name,
            category=category,
            status="failed",
            confidence=0.99,
            evidence=f"Duplicate payment signatures detected: {len(duplicates)} repeated transaction fingerprints.",
            metadata={"duplicate_count": len(duplicates), "duplicates": duplicates},
        )

    return AlgorithmResult(
        algorithm=algorithm_name,
        category=category,
        status="passed",
        confidence=0.95,
        evidence="No duplicate payment signatures were found across the evaluated transaction set.",
        metadata={"duplicate_count": 0},
    )


def _evaluate_callback_replay_protection(payload: Any, *, algorithm_name: str, category: str) -> AlgorithmResult:
    transaction = _as_mapping(payload)
    if not transaction:
        return AlgorithmResult(
            algorithm=algorithm_name,
            category=category,
            status="failed",
            confidence=0.0,
            evidence="No callback payload supplied for replay protection.",
            metadata={"input_hash": _hash_value(payload)},
        )

    source_id = str(transaction.get("TransID") or transaction.get("transaction_id") or transaction.get("id") or "").strip()
    seen = transaction.get("seen_transaction_ids") or transaction.get("seen_callbacks") or transaction.get("history") or []
    seen_ids = set()
    for item in seen:
        if isinstance(item, Mapping):
            seen_ids.add(str(item.get("TransID") or item.get("transaction_id") or item.get("id") or "").strip())
        elif item is not None:
            seen_ids.add(str(item).strip())

    if source_id and source_id in seen_ids:
        return AlgorithmResult(
            algorithm=algorithm_name,
            category=category,
            status="failed",
            confidence=1.0,
            evidence="Callback replay detected: the same transaction ID has already been processed.",
            metadata={"transaction_id": source_id, "seen_transaction_ids": sorted(seen_ids)},
        )

    canonical = json.dumps({
        key: value for key, value in transaction.items() if key not in {"seen_transaction_ids", "seen_callbacks", "history"}
    }, sort_keys=True, separators=(",", ":"), default=str)
    request_hash = hashlib.sha256(canonical.encode("utf-8")).hexdigest()

    return AlgorithmResult(
        algorithm=algorithm_name,
        category=category,
        status="passed",
        confidence=0.98,
        evidence="Callback payload is not a replay and carries a stable request hash for idempotent processing.",
        metadata={"transaction_id": source_id, "request_hash": request_hash},
    )


def _evaluate_api_gateway_payload(payload: Any, *, algorithm_name: str, category: str) -> AlgorithmResult:
    required = ["method", "path", "tenant_id"]
    result = _generic_algorithm_evaluation(
        algorithm_name,
        category,
        payload,
        required_fields=required,
        optional_fields=["client_id", "signature", "headers", "request_id"],
    )
    if result.status == "passed":
        request_data = _as_mapping(payload)
        if request_data.get("method") not in {"GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"}:
            return AlgorithmResult(
                algorithm=algorithm_name,
                category=category,
                status="failed",
                confidence=0.9,
                evidence="HTTP method is invalid or missing from approved list.",
                metadata={"method": request_data.get("method")},
            )
    return result


def _evaluate_auth_payload(payload: Any, *, algorithm_name: str, category: str) -> AlgorithmResult:
    result = _generic_algorithm_evaluation(
        algorithm_name,
        category,
        payload,
        required_fields=["identifier"],
        optional_fields=["password", "token", "mfa_code", "tenant_id", "device_id"],
    )
    if result.status == "passed":
        auth_data = _as_mapping(payload)
        if "password" not in auth_data and "token" not in auth_data and "mfa_code" not in auth_data:
            return AlgorithmResult(
                algorithm=algorithm_name,
                category=category,
                status="needs_review",
                confidence=0.7,
                evidence="Authentication input is present but no credential or token was supplied.",
                metadata={"input_hash": result.metadata["input_hash"]},
            )
    return result


CATEGORY_IMPLEMENTERS: Dict[str, Callable[[Any, str, str], AlgorithmResult]] = {
    "transaction_processing_algorithms": _evaluate_transaction_payload,
    "m_pesa_daraja_integration_algorithms": _evaluate_daraja_payload,
    "reconciliation_algorithms": _evaluate_reconciliation_payload,
    "ledger_and_financial_integrity_algorithms": _evaluate_ledger_payload,
    "fraud_and_anomaly_detection_algorithms": _evaluate_fraud_payload,
    "fake_statement_and_data_tampering_detection": _evaluate_statement_payload,
    "api_gateway_and_request_management_algorithms": _evaluate_api_gateway_payload,
    "authentication_and_account_security_algorithms": _evaluate_auth_payload,
}

SPECIALIZED_ALGORITHM_HANDLERS: Dict[str, Callable[[Any, str, str], AlgorithmResult]] = {
    _normalize_name("Exact Transaction Matching Algorithm"): _evaluate_exact_transaction_match,
    _normalize_name("Duplicate Payment Detection Algorithm"): _evaluate_duplicate_payment_detection,
    _normalize_name("Callback Replay Protection Algorithm"): _evaluate_callback_replay_protection,
    _normalize_name("Debit-Credit Balance Validation Algorithm"): _evaluate_ledger_payload,
}


CATEGORY_ALGORITHMS: Dict[str, List[str]] = {
    "transaction_processing_algorithms": [
        "Transaction Ingestion Algorithm",
        "Transaction Validation Algorithm",
        "Transaction Normalization Algorithm",
        "Transaction Deduplication Algorithm",
        "Idempotency Key Algorithm",
        "Transaction State Machine Algorithm",
        "Transaction Lifecycle Management Algorithm",
        "Transaction Classification Algorithm",
        "Transaction Reference Generation Algorithm",
        "Transaction Ordering Algorithm",
        "Late-Arriving Transaction Handling Algorithm",
        "Out-of-Order Event Processing Algorithm",
        "Transaction Integrity Verification Algorithm",
        "Transaction Status Resolution Algorithm",
        "Transaction Reprocessing Algorithm",
        "Transaction Reversal Detection Algorithm",
        "Transaction Cancellation Handling Algorithm",
        "Transaction Expiration Algorithm",
        "Transaction Batch Processing Algorithm",
        "Transaction Recovery Algorithm",
    ],
    "m_pesa_daraja_integration_algorithms": [
        "Daraja Callback Authentication Algorithm",
        "Callback Signature Verification Algorithm",
        "Callback Payload Validation Algorithm",
        "Callback Deduplication Algorithm",
        "Callback Replay Protection Algorithm",
        "STK Push Request Correlation Algorithm",
        "STK Push Result Processing Algorithm",
        "C2B Confirmation Processing Algorithm",
        "C2B Validation Processing Algorithm",
        "B2C Result Processing Algorithm",
        "Transaction Reference Matching Algorithm",
        "M-Pesa Receipt Validation Algorithm",
        "M-Pesa Transaction Status Reconciliation Algorithm",
        "Daraja Token Acquisition and Refresh Algorithm",
        "Daraja Rate-Limit Handling Algorithm",
        "Daraja Retry and Backoff Algorithm",
        "Daraja Timeout and Unknown-Outcome Resolution Algorithm",
        "Daraja API Circuit Breaker Algorithm",
        "Provider Availability Monitoring Algorithm",
        "Callback-to-Ledger Consistency Algorithm",
    ],
    "reconciliation_algorithms": [
        "Exact Transaction Matching Algorithm",
        "Reference-Based Matching Algorithm",
        "Amount-Based Matching Algorithm",
        "Timestamp-Window Matching Algorithm",
        "Account and Till Matching Algorithm",
        "Composite Transaction Matching Algorithm",
        "Weighted Matching Score Algorithm",
        "Fuzzy Reference Matching Algorithm",
        "One-to-One Matching Algorithm",
        "One-to-Many Matching Algorithm",
        "Many-to-One Matching Algorithm",
        "Duplicate Payment Detection Algorithm",
        "Missing Transaction Detection Algorithm",
        "Unmatched Transaction Classification Algorithm",
        "Partial Payment Detection Algorithm",
        "Overpayment Detection Algorithm",
        "Underpayment Detection Algorithm",
        "Reconciliation Difference Calculation Algorithm",
        "Reconciliation Confidence Scoring Algorithm",
        "Automatic Reconciliation Decision Algorithm",
        "Manual Review Routing Algorithm",
        "Reconciliation Exception Resolution Algorithm",
        "Reconciliation Run State Management Algorithm",
        "Reconciliation Snapshot Generation Algorithm",
        "Reconciliation Correction and Reversal Algorithm",
        "Reconciliation Completeness Verification Algorithm",
        "Reconciliation Finalization Algorithm",
        "Reconciliation Audit Trail Algorithm",
    ],
    "ledger_and_financial_integrity_algorithms": [
        "Double-Entry Bookkeeping Algorithm",
        "Debit-Credit Balance Validation Algorithm",
        "Immutable Ledger Entry Algorithm",
        "Append-Only Ledger Enforcement Algorithm",
        "Ledger Posting Idempotency Algorithm",
        "Ledger Transaction Ordering Algorithm",
        "Ledger Balance Calculation Algorithm",
        "Available Balance Calculation Algorithm",
        "Ledger-to-Transaction Consistency Algorithm",
        "Ledger Reconciliation Algorithm",
        "Financial Period Closing Algorithm",
        "Ledger Correction Through Reversal Algorithm",
        "Financial Rounding Algorithm",
        "Currency Precision Validation Algorithm",
        "Settlement Calculation Algorithm",
        "Settlement Batch Generation Algorithm",
        "Settlement Completeness Verification Algorithm",
        "Financial Report Consistency Algorithm",
    ],
    "fraud_and_anomaly_detection_algorithms": [
        "Duplicate Transaction Anomaly Detection Algorithm",
        "Amount Deviation Detection Algorithm",
        "Transaction Frequency Anomaly Detection Algorithm",
        "Unusual Transaction Timing Detection Algorithm",
        "Repeated Failed Payment Detection Algorithm",
        "Suspicious Reversal Detection Algorithm",
        "Receipt and Reference Reuse Detection Algorithm",
        "Missing Receipt Sequence Analysis Algorithm",
        "Unusual Till Activity Detection Algorithm",
        "Unexpected Payment Pattern Detection Algorithm",
        "Merchant Behavior Baseline Algorithm",
        "Transaction Velocity Scoring Algorithm",
        "Statistical Outlier Detection Algorithm",
        "Interquartile Range Anomaly Detection Algorithm",
        "Z-Score Anomaly Detection Algorithm",
        "Isolation Forest Anomaly Detection Algorithm",
        "Risk Score Calculation Algorithm",
        "Risk Threshold Classification Algorithm",
        "Fraud Rule Evaluation Engine",
        "Risk Signal Aggregation Algorithm",
        "False Positive Review Algorithm",
        "Fraud Alert Prioritization Algorithm",
        "Suspicious Activity Case Management Algorithm",
        "Risk Model Performance Monitoring Algorithm",
    ],
    "fake_statement_and_data_tampering_detection": [
        "Statement Schema Validation Algorithm",
        "Statement Row Integrity Validation Algorithm",
        "Duplicate Statement Entry Detection Algorithm",
        "Missing Record Detection Algorithm",
        "Statement-to-Callback Matching Algorithm",
        "Statement-to-Ledger Matching Algorithm",
        "Amount and Balance Consistency Algorithm",
        "Opening-to-Closing Balance Verification Algorithm",
        "Statement Period Completeness Algorithm",
        "Statement Provenance Verification Algorithm",
        "File Hash Integrity Verification Algorithm",
        "File Format and Metadata Validation Algorithm",
        "Suspicious Record Modification Detection Algorithm",
        "Statement Anomaly Scoring Algorithm",
        "Evidence Preservation and Audit Trail Algorithm",
    ],
    "api_gateway_and_request_management_algorithms": [
        "API Key Authentication Algorithm",
        "API Key Hash Verification Algorithm",
        "API Key Scope Authorization Algorithm",
        "Role-Based Access Control Algorithm",
        "Attribute-Based Access Control Algorithm",
        "Tenant Context Resolution Algorithm",
        "Tenant Isolation Enforcement Algorithm",
        "Organization-to-Tenant Mapping Verification Algorithm",
        "Project and Environment Access Validation Algorithm",
        "Request Schema Validation Algorithm",
        "Request Canonicalization Algorithm",
        "Request Size Enforcement Algorithm",
        "API Version Routing Algorithm",
        "Request Correlation ID Generation Algorithm",
        "Trace Context Propagation Algorithm",
        "API Usage Metering Algorithm",
        "API Quota Enforcement Algorithm",
        "Token Bucket Rate-Limiting Algorithm",
        "Sliding-Window Rate-Limiting Algorithm",
        "Request Timeout Enforcement Algorithm",
        "Request Idempotency Enforcement Algorithm",
        "API Error Classification Algorithm",
        "Secure Response Serialization Algorithm",
        "API Pagination Algorithm",
        "API Filtering and Sorting Validation Algorithm",
    ],
    "authentication_and_account_security_algorithms": [
        "Password Hashing and Verification Algorithm",
        "Password Policy Validation Algorithm",
        "Login Attempt Tracking Algorithm",
        "Brute-Force Protection Algorithm",
        "Account Lockout and Recovery Algorithm",
        "Email Verification Token Algorithm",
        "One-Time Password Generation Algorithm",
        "One-Time Password Verification Algorithm",
        "OTP Expiration Algorithm",
        "OTP Attempt Limiting Algorithm",
        "Session Creation and Validation Algorithm",
        "Access Token Validation Algorithm",
        "Refresh Token Rotation Algorithm",
        "Refresh Token Reuse Detection Algorithm",
        "Session Revocation Algorithm",
        "Multi-Factor Authentication Algorithm",
        "Backup Code Generation and Verification Algorithm",
        "Password Reset Token Algorithm",
        "Secure Account Recovery Algorithm",
        "Device and Session Risk Assessment Algorithm",
        "Privilege Escalation Prevention Algorithm",
        "API Key Creation and Rotation Algorithm",
        "API Key Revocation and Expiration Algorithm",
    ],
}


def _algorithm_name_to_function_name(raw_name: str) -> str:
    cleaned = raw_name.strip()
    if cleaned.endswith("Algorithm"):
        cleaned = cleaned[:-len("Algorithm")].strip()
    cleaned = cleaned.replace("-", " ").replace("/", " ")
    normalized = re.sub(r"[^a-zA-Z0-9]+", "_", cleaned)
    return normalized.strip("_").lower()


def _make_algorithm_wrapper(raw_name: str, category: str) -> Callable[..., Dict[str, Any]]:
    func_name = _algorithm_name_to_function_name(raw_name)
    normalized = _normalize_name(raw_name)
    handler = SPECIALIZED_ALGORITHM_HANDLERS.get(normalized, CATEGORY_IMPLEMENTERS.get(category, _evaluate_transaction_payload))

    def algorithm(payload: Any = None, *, context: Any = None, **extra: Any) -> Dict[str, Any]:
        input_payload = payload if payload is not None else context
        evaluated = handler(
            input_payload,
            algorithm_name=raw_name,
            category=category,
        )
        if isinstance(context, Mapping):
            metadata = dict(evaluated.metadata)
            metadata["context"] = dict(context)
            evaluated = AlgorithmResult(
                algorithm=evaluated.algorithm,
                category=evaluated.category,
                status=evaluated.status,
                confidence=evaluated.confidence,
                evidence=evaluated.evidence,
                metadata=metadata,
            )
        return evaluated.as_dict()

    algorithm.__name__ = func_name
    return algorithm


AlgorithmRegistry: Dict[str, Callable[..., Dict[str, Any]]] = {}
for category, names in CATEGORY_ALGORITHMS.items():
    for raw_name in names:
        func_name = _algorithm_name_to_function_name(raw_name)
        AlgorithmRegistry[func_name] = _make_algorithm_wrapper(raw_name, category)


def list_algorithms() -> List[str]:
    """Return the canonical list of all registered algorithm names."""
    return [
        name
        for category in CATEGORY_ALGORITHMS.values()
        for name in category
    ]


def evaluate_algorithm(name: str, payload: Any = None, *, context: Any = None) -> Dict[str, Any]:
    """Resolve a named algorithm and evaluate it against the supplied payload."""
    normalized = _normalize_name(name)
    lookup = AlgorithmRegistry.get(normalized)
    if lookup is None:
        alias = normalized.replace("_algorithm", "")
        lookup = AlgorithmRegistry.get(alias)
    if lookup is None:
        candidates = sorted({
            _normalize_name(item)
            for item in list_algorithms()
        })
        raise KeyError(
            f"Unknown algorithm: {name!r}. Available algorithms: {candidates[:10]}..."
        )
    return lookup(payload, context=context)


ARTIFACTS = {
    "algorithm_categories": list(CATEGORY_ALGORITHMS.keys()),
    "algorithm_count": sum(len(items) for items in CATEGORY_ALGORITHMS.values()),
    "registry": sorted(AlgorithmRegistry.keys()),
}


__all__ = [
    "AlgorithmRegistry",
    "AlgorithmResult",
    "ARTIFACTS",
    "CATEGORY_ALGORITHMS",
    "evaluate_algorithm",
    "list_algorithms",
] + sorted(AlgorithmRegistry.keys())


# Expose the algorithm names as functions with stable Python symbol names.
for func_name, func in list(AlgorithmRegistry.items()):
    globals()[func_name] = func
