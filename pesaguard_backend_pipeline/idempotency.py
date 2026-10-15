from __future__ import annotations

import hashlib
from datetime import datetime
from typing import Any, Dict, Optional

from normalization import DEFAULT_CURRENCY, normalize_amount, normalize_currency

def _normalize_msisdn(value: Any) -> str:
    return "".join(ch for ch in str(value or "") if ch.isdigit())


def _normalize_amount(value: Any, currency: str) -> str:
    return normalize_amount(value, currency=currency)


def _normalize_time(value: Any) -> str:
    text = str(value or "").strip()
    if not text:
        return ""

    if len(text) == 14 and text.isdigit():
        return text

    if text.endswith("Z"):
        text = text[:-1] + "+00:00"

    try:
        dt = datetime.fromisoformat(text)
        return dt.strftime("%Y%m%d%H%M%S")
    except ValueError:
        return text


def derive_idempotency_key(payload: Dict) -> str:
    trans_id = str(payload.get("TransID") or payload.get("trans_id") or "").strip()
    if trans_id:
        return f"transid:{trans_id.upper()}"

    msisdn = _normalize_msisdn(payload.get("MSISDN") or payload.get("msisdn") or payload.get("phone_number"))
    currency = normalize_currency(payload.get("Currency") or payload.get("currency") or DEFAULT_CURRENCY)
    amount = _normalize_amount(
        payload.get("TransAmount", payload.get("amount")),
        currency,
    )
    trans_time = _normalize_time(payload.get("TransTime") or payload.get("trans_time") or payload.get("timestamp"))

    canonical = f"{currency}|{msisdn}|{amount}|{trans_time}"
    digest = hashlib.sha256(canonical.encode("utf-8")).hexdigest()
    return f"hash:{digest}"
