"""Reconciliation helpers: normalization and basic matching utilities.

- normalize_daraja_event(payload, tenant_id="default") -> dict with canonical keys:
  'TransID', 'TransAmount', 'TransTime', 'MSISDN', plus 'reference'/'amount'/'timestamp' aliases.
- exact_match(tx, settlement) -> bool
- find_exact_match(tx, settlements) -> dict|None
- time_window_match(tx, settlements, window_seconds=300) -> dict|None

Designed to be dependency-light (stdlib only).
"""
from __future__ import annotations
from decimal import Decimal
from typing import Dict, Iterable, Optional

from normalization import (
    DEFAULT_CURRENCY,
    NormalizationError,
    normalize_amount,
    normalize_currency,
)
from datetime import datetime, timedelta
import logging
import re

logger = logging.getLogger(__name__)

DAR_A_TIME_RE = re.compile(r"^\d{14}$")  # YYYYMMDDHHMMSS


def parse_daraja_time(value: str) -> Optional[datetime]:
    """Parse Daraja timestamp formats like YYYYMMDDHHMMSS or ISO-8601 strings.
    Returns naive UTC datetime on success, None on failure.
    """
    if not value:
        return None
    if isinstance(value, str):
        val = value.strip()
        if DAR_A_TIME_RE.match(val):
            try:
                return datetime(
                    int(val[0:4]),
                    int(val[4:6]),
                    int(val[6:8]),
                    int(val[8:10]),
                    int(val[10:12]),
                    int(val[12:14]),
                )
            except Exception:
                return None
        # ISO fallback
        try:
            # Replace trailing Z -> +00:00 for fromisoformat
            return datetime.fromisoformat(val.replace("Z", "+00:00")).astimezone(tz=None).replace(tzinfo=None)
        except Exception:
            return None
    return None


def _get_reference(record: Dict) -> Optional[str]:
    """Extract a canonical reference from a record."""
    for k in ("TransID", "reference", "transaction_reference", "tx_ref", "merchant_ref", "checkoutRequestID"):
        v = record.get(k)
        if v:
            return str(v).strip()
    return None


def _get_currency(record: Dict) -> str:
    """Normalize an explicit record currency or apply the legacy KES default."""
    for key in ("Currency", "currency"):
        if key in record:
            if record[key] is None or not str(record[key]).strip():
                raise NormalizationError("currency must not be empty when supplied")
            return normalize_currency(record[key])
    return DEFAULT_CURRENCY


def _get_amount(record: Dict) -> Optional[Decimal]:
    """Extract an exact amount at the record currency's minor-unit scale."""
    for k in ("TransAmount", "amount", "amt", "transaction_amount"):
        v = record.get(k)
        if v is None:
            continue
        currency = _get_currency(record)
        return Decimal(normalize_amount(v, currency=currency))
    return None


def _get_timestamp(record: Dict) -> Optional[datetime]:
    """Try common timestamp fields and parse them."""
    for k in ("TransTime", "timestamp", "time", "created_at", "trans_time"):
        v = record.get(k)
        if not v:
            continue
        # If it's already a datetime, return it (caller must ensure timezone handling)
        if isinstance(v, datetime):
            return v
        # Parse known Daraja format or ISO
        parsed = parse_daraja_time(str(v))
        if parsed:
            return parsed
    return None


def normalize_daraja_event(payload: Dict, tenant_id: str = "default") -> Dict:
    """Return a normalized event shape derived from Daraja payloads.

    Keeps keys consistent with reconciliation engine expectations:
    - TransID, TransAmount, TransTime, MSISDN
    - Also returns 'reference', 'amount', 'timestamp' aliases to simplify matching.
    """
    out: Dict = {}
    # STK Push nested shape handled upstream in validators; fallback to common fields
    trans_id = payload.get("TransID") or payload.get("TransactionID") or payload.get("ReceiptNumber") or payload.get("CheckoutRequestID")
    if trans_id:
        out["TransID"] = str(trans_id).strip()

    # Amount
    amt = next(
        (
            payload[key]
            for key in ("TransAmount", "amount", "Amount")
            if payload.get(key) is not None
        ),
        None,
    )
    if amt is not None:
        currency = _get_currency(payload)
        normalized_amount = normalize_amount(amt, currency=currency)
        out["TransAmount"] = normalized_amount
        out["amount"] = normalized_amount
        out["Currency"] = currency
        out["currency"] = currency

    # Timestamp
    tt = payload.get("TransTime") or payload.get("TransactionDate") or payload.get("timestamp")
    parsed_ts = None
    if tt:
        parsed_ts = parse_daraja_time(str(tt))
    if parsed_ts:
        out["TransTime"] = parsed_ts.strftime("%Y-%m-%dT%H:%M:%S")
        out["timestamp"] = parsed_ts
    else:
        # If no parsable time, skip timestamp keys
        pass

    # MSISDN / phone
    msisdn = payload.get("MSISDN") or payload.get("PhoneNumber") or payload.get("msisdn")
    if msisdn:
        out["MSISDN"] = str(msisdn).strip()
        out["phone_number"] = out["MSISDN"]

    # Provide handy aliases for reconciliation helpers
    ref = _get_reference(payload)
    if ref:
        out["reference"] = ref

    return out


def exact_match(tx: Dict, settlement: Dict) -> bool:
    """Return True if transaction and settlement match exactly on reference and amount (when present)."""
    ref_tx = _get_reference(tx)
    ref_st = _get_reference(settlement)
    if not ref_tx or not ref_st:
        return False
    if ref_tx != ref_st:
        return False

    try:
        if _get_currency(tx) != _get_currency(settlement):
            return False
        amt_tx = _get_amount(tx)
        amt_st = _get_amount(settlement)
    except NormalizationError:
        return False
    if (amt_tx is not None) and (amt_st is not None):
        return amt_tx == amt_st
    return amt_tx is None and amt_st is None


def find_exact_match(tx: Dict, settlements: Iterable[Dict]) -> Optional[Dict]:
    """Return the first settlement that exactly matches tx (or None)."""
    for s in settlements:
        if exact_match(tx, s):
            return s
    return None


def time_window_match(tx: Dict, settlements: Iterable[Dict], window_seconds: int = 300) -> Optional[Dict]:
    """Find a settlement that matches by reference (or amount) within +/- window_seconds of tx timestamp.

    Matching strategy:
    - Prefer exact reference + amount equality regardless of timestamp.
    - Otherwise, match same reference within the window_seconds.
    - If no reference, fall back to amount + timestamp.
    """
    # Prefer exact
    exact = find_exact_match(tx, settlements)
    if exact:
        return exact

    try:
        tx_currency = _get_currency(tx)
        amt_tx = _get_amount(tx)
    except NormalizationError:
        return None
    tx_ts = _get_timestamp(tx)
    ref_tx = _get_reference(tx)

    window = timedelta(seconds=window_seconds)

    for s in settlements:
        try:
            if tx_currency != _get_currency(s):
                continue
            amt_s = _get_amount(s)
        except NormalizationError:
            continue
        s_ts = _get_timestamp(s)
        # Match by reference inside window
        if ref_tx:
            ref_s = _get_reference(s)
            if ref_s and (ref_s == ref_tx):
                if (tx_ts is None) or (s_ts is None) or (abs(tx_ts - s_ts) <= window):
                    return s
        # Fallback: amount + timestamp
        if amt_tx is not None:
            if (amt_s is not None) and (amt_s == amt_tx):
                if (tx_ts is None) or (s_ts is None) or (abs(tx_ts - s_ts) <= window):
                    return s
    return None
