"""Robust M-Pesa transaction anomaly detection engine for PesaGuard."""

from __future__ import annotations

import logging
import os
from datetime import datetime
from decimal import Decimal, InvalidOperation
from typing import Any, Dict, List, Optional, Set

from normalization import (
    DEFAULT_CURRENCY,
    NormalizationError,
    normalize_amount,
    normalize_currency,
)

logger = logging.getLogger("pesaguard.anomaly_rules")

# ============================================================================
# PILOT THRESHOLDS (per-tenant overrides supported via dynamic context)
# ============================================================================

DEFAULT_LARGE_AMOUNT_KES = int(os.getenv("ANOMALY_LARGE_AMOUNT_KES", "150000"))
DEFAULT_CALLBACK_DELAY_MINUTES = int(os.getenv("ANOMALY_CALLBACK_DELAY_MIN", "10"))
DEFAULT_ANOMALY_SCORE_THRESHOLD = float(os.getenv("ANOMALY_SCORE_THRESHOLD", "0.8"))
DEFAULT_OFF_HOURS_START_UTC = int(os.getenv("ANOMALY_OFF_HOURS_START", "0"))
DEFAULT_OFF_HOURS_END_UTC = int(os.getenv("ANOMALY_OFF_HOURS_END", "4"))
DEFAULT_OFF_HOURS_PENALTY = float(os.getenv("ANOMALY_OFF_HOURS_PENALTY", "0.2"))


def check_for_anomalies(
    event: Dict[str, Any],
    seen_trans_ids: Set[str],
    tenant_settings: Optional[Dict[str, Any]] = None,
) -> List[str]:
    """
    Runs comprehensive anomaly detection checks against a single transaction event.
    Returns a list of human-readable anomaly descriptions (empty if clean).
    
    Severity Mapping Context:
      - duplicate_transaction_id: HIGH (Idempotency failure)
      - amount_exceeds_threshold_*: MEDIUM (Potential fraud/misuse)
      - invalid_or_zero_amount: HIGH (Data quality issue)
      - high_anomaly_score_*: MEDIUM (Statistical heuristic anomaly)
    """
    if not isinstance(event, dict):
        logger.error("Invalid event structure passed to anomaly detection: %s", type(event))
        return ["invalid_event_structure"]

    tenant_settings = tenant_settings or {}
    
    # Resolve per-tenant configuration overrides cleanly with fallbacks
    large_amount_threshold = int(
        tenant_settings.get("anomaly_large_amount_kes") 
        or tenant_settings.get("LARGE_AMOUNT_THRESHOLD_KES") 
        or DEFAULT_LARGE_AMOUNT_KES
    )
    score_threshold = float(
        tenant_settings.get("anomaly_score_threshold") 
        or tenant_settings.get("ANOMALY_SCORE_THRESHOLD") 
        or DEFAULT_ANOMALY_SCORE_THRESHOLD
    )

    anomalies: List[str] = []

    try:
        if _is_duplicate(event, seen_trans_ids):
            anomalies.append("duplicate_transaction_id")

        if _has_invalid_amount(event):
            anomalies.append("invalid_or_zero_amount")
        else:
            currency = _event_currency(event)
            threshold = _threshold_for_currency(
                currency, tenant_settings, large_amount_threshold
            )
            if threshold is not None and _amount(event) > threshold:
                anomalies.append(f"amount_exceeds_threshold_{threshold}_{currency}")

            score = score_transaction_anomaly(
                event, tenant_settings, large_amount_threshold
            )
            if score > score_threshold:
                anomalies.append(f"high_anomaly_score_{round(score, 2)}")

    except (InvalidOperation, TypeError, ValueError) as exc:
        logger.exception("Unexpected error during anomaly evaluation for event: %s", exc)
        anomalies.append("anomaly_evaluation_error")

    return anomalies


def score_transaction_anomaly(
    event: Dict[str, Any],
    tenant_settings: Optional[Dict[str, Any]] = None,
    large_amount_threshold: int = DEFAULT_LARGE_AMOUNT_KES,
) -> float:
    """
    Statistical heuristic anomaly scoring (0.0 to 1.0).
    Combines signals across amount extremeness, off-hours timing, and unusual formatting.
    
    Returns:
        Float score from 0.0 (normal) to 1.0 (highly suspicious).
    """
    tenant_settings = tenant_settings or {}
    score = 0.0

    amount = _amount(event)
    currency = _event_currency(event)
    threshold = _threshold_for_currency(
        currency, tenant_settings, large_amount_threshold
    )

    # Signal 1: Extreme amount tiering
    if threshold is not None:
        if amount > threshold * Decimal("1.5"):
            score += 0.5
        elif amount > threshold:
            score += 0.3

    # Signal 2: Off-hours timing check (UTC timezone evaluation)
    off_hours_start = int(tenant_settings.get("anomaly_off_hours_start", DEFAULT_OFF_HOURS_START_UTC))
    off_hours_end = int(tenant_settings.get("anomaly_off_hours_end", DEFAULT_OFF_HOURS_END_UTC))
    off_hours_penalty = float(tenant_settings.get("anomaly_off_hours_penalty", DEFAULT_OFF_HOURS_PENALTY))

    trans_time = str(event.get("TransTime", ""))
    try:
        event_dt = None
        if len(trans_time) >= 14 and trans_time[:14].isdigit():
            hour = int(trans_time[8:10])
            event_dt = datetime(int(trans_time[0:4]), int(trans_time[4:6]), int(trans_time[6:8]), hour)
        elif "T" in trans_time:
            event_dt = datetime.fromisoformat(trans_time.replace("Z", "+00:00"))

        if event_dt and off_hours_penalty > 0:
            hour = event_dt.hour
            if off_hours_start < off_hours_end:
                if off_hours_start <= hour < off_hours_end:
                    score += off_hours_penalty
            else:
                # Wraparound hours (e.g., 22:00 to 04:00 UTC)
                if hour >= off_hours_start or hour < off_hours_end:
                    score += off_hours_penalty
    except (ValueError, AttributeError):
        pass  # Malformed timestamps are safely ignored without penalty

    # Signal 3: Unusual amount patterning (non-round figures on large transactions)
    if threshold is not None and amount > threshold and amount % 10000 != 0:
        score += 0.15

    # Clamp final score securely between 0.0 and 1.0
    return max(0.0, min(1.0, score))


def _is_duplicate(event: Dict[str, Any], seen_trans_ids: Set[str]) -> bool:
    """Check if transaction ID has been tracked previously in the current batch."""
    trans_id = str(event.get("TransID", "")).strip()
    if not trans_id:
        return False
    return trans_id in seen_trans_ids


def _is_unusually_large(event: Dict[str, Any], threshold: int) -> bool:
    """Check if transaction amount exceeds the active threshold."""
    try:
        currency = _event_currency(event)
        active_threshold = _threshold_for_currency(currency, {}, threshold)
        return active_threshold is not None and _amount(event) > active_threshold
    except NormalizationError:
        return False


def _has_invalid_amount(event: Dict[str, Any]) -> bool:
    """Classify invalid, non-finite, non-positive, or unsupported money."""
    try:
        _amount(event)
        return False
    except NormalizationError:
        return True


def _event_currency(event: Dict[str, Any]) -> str:
    return normalize_currency(
        event.get("Currency") or event.get("currency") or DEFAULT_CURRENCY
    )


def _amount(event: Dict[str, Any]) -> Decimal:
    currency = _event_currency(event)
    return Decimal(
        normalize_amount(
            event.get("TransAmount", event.get("amount")),
            currency=currency,
        )
    )


def _threshold_for_currency(
    currency: str,
    tenant_settings: Dict[str, Any],
    default_threshold: int,
) -> Decimal | None:
    thresholds = tenant_settings.get("anomaly_large_amount_thresholds", {})
    if currency == "KES":
        raw_threshold = (
            tenant_settings.get("anomaly_large_amount_kes")
            or tenant_settings.get("LARGE_AMOUNT_THRESHOLD_KES")
            or default_threshold
        )
    elif isinstance(thresholds, dict) and currency in thresholds:
        raw_threshold = thresholds[currency]
    else:
        return None
    try:
        threshold = Decimal(str(raw_threshold))
    except Exception as exc:
        raise NormalizationError("amount threshold must be a valid decimal") from exc
    if not threshold.is_finite() or threshold <= 0:
        raise NormalizationError("amount threshold must be a positive finite decimal")
    return threshold
