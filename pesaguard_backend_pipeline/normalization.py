"""Deterministic normalization rules for provider data."""

from __future__ import annotations

import re
from datetime import datetime, timezone
from decimal import Decimal, DecimalException, InvalidOperation, ROUND_HALF_UP
from typing import Any, Mapping
from zoneinfo import ZoneInfo


class NormalizationError(ValueError):
    """Raised when a provider value cannot be normalized safely."""


class CurrencyMismatchError(ValueError):
    """Raised when two amounts in different currencies are combined.

    Money is never converted or guessed across currencies: a mixed-currency
    operation is a hard error so no reconciliation, aggregation or report can
    silently add KES to USD (Rule #11, Rule #57).
    """


_UNSET = object()



# --- Authoritative monetary contract -----------------------------------------
#
# Every ingestion route, scoring rule, aggregation and reconciliation path that
# reads a provider amount MUST go through the helpers in this block. There is
# exactly one definition of "what an amount is" in PesaGuard.
#
# Contract:
#   * amounts are positive finite Decimal values and carry a supported ISO currency.
#   * persistence has 16 integer digits and up to 3 fractional digits.
#   * input finer than a currency minor unit is rejected, never rounded.
#   * ROUND_HALF_UP is reserved for explicit quantization of derived values.
#   * values above the storage ceiling are rejected before persistence or scoring.

MINOR_UNITS = 2
CENT = Decimal("0.01")
MINOR_UNIT_QUANTUM = Decimal("0.01")
DEFAULT_CURRENCY = "KES"

# Numeric(19, 3) holds 16 integer digits.
MAX_TRANSACTION_AMOUNT = Decimal("9999999999999999.999")
MIN_TRANSACTION_AMOUNT = CENT

# Explicit supported currencies. Unknown codes are rejected rather than assigned
# an assumed two-decimal scale.
CURRENCY_MINOR_UNITS: dict[str, int] = {
    "KES": 2, "TZS": 2, "UGX": 0, "USD": 2, "EUR": 2, "GBP": 2, "BHD": 3,
}

# Bounds shared by every scoring path so features cannot be driven to infinity
# by an out-of-range provider value.
MAX_SCORING_AMOUNT = float(MAX_TRANSACTION_AMOUNT)
MIN_SCORING_AMOUNT = float(MIN_TRANSACTION_AMOUNT)


class Money:
    """An amount that carries its currency and cannot be compared across currencies.

    Every comparison, arithmetic or aggregation of Money requires the same
    currency, so currencies cannot be silently mixed in reconciliation,
    aggregation or dashboards (Rule #11, Rule #57).

    The original provider value is kept verbatim on ``original`` so that audit
    and dispute investigation can always see what the provider actually sent,
    independently of the normalized form (Rule #34).
    """

    __slots__ = ("amount", "currency", "original")

    def __init__(self, amount: Any, currency: Any, *, original: Any = _UNSET) -> None:
        self.currency = normalize_currency(currency, default=DEFAULT_CURRENCY)
        self.amount = Decimal(normalize_amount(amount, currency=self.currency))
        self.original = amount if original is _UNSET else original

    def _require_same_currency(self, other: "Money", operation: str) -> None:
        if self.currency != other.currency:
            raise CurrencyMismatchError(
                f"cannot {operation} {self.currency} with {other.currency}"
            )

    def __add__(self, other: "Money") -> "Money":
        if not isinstance(other, Money):
            return NotImplemented
        self._require_same_currency(other, "add")
        return Money(self.amount + other.amount, self.currency, original=self.original)

    def __sub__(self, other: "Money") -> "Money":
        if not isinstance(other, Money):
            return NotImplemented
        self._require_same_currency(other, "subtract")
        return Money(self.amount - other.amount, self.currency, original=self.original)

    def __eq__(self, other: object) -> bool:
        if not isinstance(other, Money):
            return NotImplemented
        self._require_same_currency(other, "compare")
        return self.amount == other.amount

    def __lt__(self, other: "Money") -> bool:
        if not isinstance(other, Money):
            return NotImplemented
        self._require_same_currency(other, "compare")
        return self.amount < other.amount

    def __le__(self, other: "Money") -> bool:
        if not isinstance(other, Money):
            return NotImplemented
        self._require_same_currency(other, "compare")
        return self.amount <= other.amount

    def __gt__(self, other: "Money") -> bool:
        if not isinstance(other, Money):
            return NotImplemented
        self._require_same_currency(other, "compare")
        return self.amount > other.amount

    def __ge__(self, other: "Money") -> bool:
        if not isinstance(other, Money):
            return NotImplemented
        self._require_same_currency(other, "compare")
        return self.amount >= other.amount

    def __hash__(self) -> int:
        return hash((self.amount, self.currency))

    def __repr__(self) -> str:
        return f"Money({self.amount!s} {self.currency})"

    def as_canonical_string(self) -> str:
        return f"{self.amount:.{CURRENCY_MINOR_UNITS[self.currency]}f}"


def parse_decimal(value: Any) -> Decimal:
    """Parse any provider amount representation into an exact Decimal.

    Accepts Decimal, int, float and str (including scientific notation,
    thousands separators and leading '+'/'-' signs). Rejects bool, bytes,
    non-finite values and anything that is not a number.

    This is the ONLY place a provider amount is converted into a Decimal. It
    never rounds and never truncates: the exact provider value is returned so
    callers can decide between rejection and explicit quantization (Rule #33).
    """
    if isinstance(value, bool):
        raise NormalizationError("amount must be a numeric value")
    if isinstance(value, Decimal):
        candidate = value
    elif isinstance(value, int):
        candidate = Decimal(value)
    elif isinstance(value, float):
        # float("nan") / float("inf") must be rejected, not stringified into a
        # Decimal that would silently become non-finite downstream.
        if value != value or value in (float("inf"), float("-inf")):
            raise NormalizationError("amount must be a finite number")
        # repr() gives the shortest string that round-trips the binary float,
        # so no precision is invented.
        candidate = Decimal(repr(value))
    elif isinstance(value, str):
        text = value.strip()
        if not text:
            raise NormalizationError("amount must be a valid decimal")
        numeric = r"(?:\d+|\d{1,3}(?:,\d{3})+)(?:\.\d*)?|\.\d+"
        if re.fullmatch(r"[+-]?(?:s?nan|inf(?:inity)?)", text, flags=re.IGNORECASE):
            raise NormalizationError("amount must be finite")
        if not re.fullmatch(rf"[+-]?(?:{numeric})(?:[eE][+-]?\d+)?", text):
            raise NormalizationError("amount must be a valid decimal")
        try:
            candidate = Decimal(text.replace(",", ""))
        except (InvalidOperation, ValueError) as exc:
            raise NormalizationError("amount must be a valid decimal") from exc
    else:
        raise NormalizationError("amount must be a numeric value")

    if not candidate.is_finite():
        raise NormalizationError("amount must be finite")
    return candidate


def currency_quantum(currency: Any = DEFAULT_CURRENCY) -> Decimal:
    """Return the exact minor-unit quantum for a supported currency."""
    code = normalize_currency(currency, default=DEFAULT_CURRENCY)
    return Decimal(1).scaleb(-CURRENCY_MINOR_UNITS[code])


def quantize_money(
    value: Decimal,
    *,
    currency: Any = DEFAULT_CURRENCY,
    rounding: str = ROUND_HALF_UP,
) -> Decimal:
    """Round a derived value to a currency minor unit with storage-bound checks.

    Provider input must use ``normalize_amount`` instead, which rejects excess
    precision rather than silently rounding the original financial value.
    """
    if not value.is_finite():
        raise NormalizationError("amount must be finite")
    if abs(value) > MAX_TRANSACTION_AMOUNT:
        raise NormalizationError("amount exceeds the supported transaction precision")
    try:
        result = value.quantize(currency_quantum(currency), rounding=rounding)
    except (DecimalException, ValueError) as exc:
        raise NormalizationError("amount exceeds the supported transaction precision") from exc
    if abs(result) > MAX_TRANSACTION_AMOUNT:
        raise NormalizationError("amount exceeds the supported transaction precision")
    return result


def normalize_amount(value: Any, *, currency: Any = DEFAULT_CURRENCY) -> str:
    """Normalize a positive provider amount to its currency's minor-unit scale.

    Rejects non-finite values, excess decimal precision, non-positive amounts,
    unsupported currencies and values beyond the storage ceiling.
    The provider's original value is never overwritten -- it is preserved
    separately in the raw payload for audit and dispute investigation (Rule #34).
    """
    code = normalize_currency(currency, default=DEFAULT_CURRENCY)
    amount = parse_decimal(value)
    if amount <= 0:
        raise NormalizationError("amount must be greater than zero")
    normalized = quantize_money(amount, currency=code)
    if amount != normalized:
        raise NormalizationError(
            f"amount precision exceeds the minor unit for {code} "
            f"({CURRENCY_MINOR_UNITS[code]} decimal places)"
        )
    if normalized < currency_quantum(code):
        raise NormalizationError(f"amount is below one minor unit for {code}")
    return f"{normalized:.{CURRENCY_MINOR_UNITS[code]}f}"


def format_money(value: Any, currency: Any = DEFAULT_CURRENCY) -> str:
    """Format an already-valid monetary value at its canonical currency scale."""
    code = normalize_currency(currency, default=DEFAULT_CURRENCY)
    amount = parse_decimal(value)
    normalized = quantize_money(amount, currency=code)
    if amount != normalized:
        raise NormalizationError(
            f"amount precision exceeds the minor unit for {code}"
        )
    return f"{normalized:.{CURRENCY_MINOR_UNITS[code]}f}"


def normalize_timestamp(value: Any, *, default_timezone: str = "Africa/Nairobi") -> str:
    text = str(value or "").strip()
    if not text:
        raise NormalizationError("timestamp is required")
    text = re.sub(r"\s+(EAT|UTC)$", lambda match: " +03:00" if match.group(1) == "EAT" else " +00:00", text, flags=re.IGNORECASE)
    parsed = None
    for candidate in (text, text.replace("Z", "+00:00")):
        try:
            parsed = datetime.fromisoformat(candidate)
            break
        except ValueError:
            continue
    if parsed is None:
        for pattern in ("%Y%m%d%H%M%S", "%Y-%m-%d %H:%M", "%Y-%m-%d %H:%M:%S"):
            try:
                parsed = datetime.strptime(text, pattern)
                break
            except ValueError:
                continue
    if parsed is None:
        raise NormalizationError("timestamp must be ISO-8601 or a supported provider timestamp")
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=ZoneInfo(default_timezone))
    return parsed.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def normalize_currency(value: Any, *, default: str = "KES") -> str:
    aliases = {
        "KSH": "KES",
        "KSHS": "KES",
        "KENYAN SHILLING": "KES",
        "US DOLLAR": "USD",
        "$": "USD",
    }
    currency = str(value or default).strip().upper()
    currency = aliases.get(currency, currency)
    if currency not in CURRENCY_MINOR_UNITS:
        raise NormalizationError(f"unsupported ISO currency code: {currency or '<empty>'}")
    return currency


def normalize_phone(value: Any, *, default_country_code: str = "254") -> str:
    text = str(value or "").strip()
    if text.lower() in {"", "unknown", "n/a", "none"}:
        return "unknown"
    digits = re.sub(r"\D", "", text)
    if digits.startswith("00"):
        digits = digits[2:]
    if digits.startswith("0"):
        digits = default_country_code + digits[1:]
    if not re.fullmatch(r"\d{10,15}", digits):
        raise NormalizationError("phone number must contain 10 to 15 digits")
    return f"+{digits}"


def normalize_provider(value: Any) -> str:
    provider = re.sub(r"[^a-z0-9]+", " ", str(value or "").strip().lower()).strip()
    aliases = {
        "mpesa": "mpesa",
        "m pesa": "mpesa",
        "safaricom": "safaricom",
        "airtel": "airtel-money",
        "airtel money": "airtel-money",
        "t kash": "t-kash",
    }
    normalized = aliases.get(provider, provider.replace(" ", "-"))
    if not normalized:
        raise NormalizationError("provider is required")
    return normalized


def normalize_transaction_type(value: Any) -> str:
    text = re.sub(r"[^a-z0-9]+", "_", str(value or "PAYMENT").strip().lower()).strip("_")
    aliases = {"pay_bill": "PAYMENT", "c2b": "PAYMENT", "stk_push": "PAYMENT", "credit": "PAYMENT", "debit": "PAYOUT"}
    return aliases.get(text, text.upper() or "PAYMENT")


def normalize_status(value: Any) -> str:
    text = re.sub(r"[^a-z0-9]+", "_", str(value or "RECEIVED").strip().lower()).strip("_")
    aliases = {"success": "COMPLETED", "successful": "COMPLETED", "pending": "PENDING", "failed": "FAILED", "reversed": "REVERSED"}
    return aliases.get(text, text.upper() or "RECEIVED")


def normalize_identifier(value: Any) -> str:
    identifier = re.sub(r"\s+", "", str(value or "")).strip()
    if not identifier:
        raise NormalizationError("identifier is required")
    return identifier.upper()


def normalize_location(value: Any) -> Dict[str, str]:
    if isinstance(value, Mapping):
        return {
            key: str(item).strip().upper() if key in {"country", "country_code", "region"} else str(item).strip()
            for key, item in value.items()
            if item is not None and str(item).strip()
        }
    text = str(value or "").strip()
    return {"value": text} if text else {}