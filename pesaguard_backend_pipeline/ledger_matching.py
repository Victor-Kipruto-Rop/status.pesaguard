from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone
from decimal import Decimal
from typing import Any, Sequence

from purchase_orders import LedgerEntry, PurchaseOrder


VALID_MATCH_STATUSES = {
    "matched",
    "partial_match",
    "manual_review",
    "unmatched",
    "duplicate",
    "rejected",
}


@dataclass(frozen=True)
class ValidationReport:
    status: str
    errors: tuple[str, ...] = ()
    warnings: tuple[str, ...] = ()

    @property
    def is_valid(self) -> bool:
        return self.status == "validated" and not self.errors

    def as_dict(self) -> dict[str, Any]:
        return {
            "status": self.status,
            "errors": list(self.errors),
            "warnings": list(self.warnings),
            "is_valid": self.is_valid,
        }


@dataclass(frozen=True)
class PersistedMatchRecord:
    record_id: str
    purchase_order_id: str
    ledger_entry_id: str
    status: str
    confidence: float
    score: int
    reasons: tuple[str, ...]
    created_at: datetime

    def as_dict(self) -> dict[str, Any]:
        return {
            "record_id": self.record_id,
            "purchase_order_id": self.purchase_order_id,
            "ledger_entry_id": self.ledger_entry_id,
            "status": self.status,
            "confidence": self.confidence,
            "score": self.score,
            "reasons": list(self.reasons),
            "created_at": self.created_at.isoformat(),
        }


@dataclass
class MatchResult:
    purchase_order: PurchaseOrder
    ledger_entry: LedgerEntry
    status: str
    confidence: float
    score: int
    reasons: list[str]
    validation_status: str = "validated"
    validation_errors: list[str] = None
    duplicate: bool = False

    def __post_init__(self):
        if self.validation_errors is None:
            self.validation_errors = []


class LedgerMatcher:
    """Advanced ledger matching using reference, amount, phone, and time signals."""

    def __init__(
        self,
        *,
        tolerance_amount: Decimal | str | float = Decimal("0.01"),
        tolerance_minutes: int = 60,
    ):
        self.tolerance_amount = Decimal(str(tolerance_amount))
        self.tolerance_minutes = max(0, int(tolerance_minutes))

    def validate_purchase_order(self, order: PurchaseOrder) -> ValidationReport:
        errors: list[str] = []
        warnings: list[str] = []
        if not (order and getattr(order, "po_number", "")):
            errors.append("missing_po_number")
        if order is not None and order.amount is None:
            errors.append("missing_amount")
        elif order is not None and order.amount is not None and order.amount <= 0:
            errors.append("invalid_amount")
        if order is not None and order.phone_number and not order.phone_number.startswith("+254"):
            warnings.append("non_standard_phone_format")
        return ValidationReport("validated" if not errors else "rejected", tuple(errors), tuple(warnings))

    def validate_ledger_entry(self, entry: LedgerEntry) -> ValidationReport:
        errors: list[str] = []
        warnings: list[str] = []
        if not (entry and getattr(entry, "entry_id", "")):
            errors.append("missing_entry_id")
        if entry is not None and entry.amount is None:
            errors.append("missing_amount")
        elif entry is not None and entry.amount is not None and entry.amount <= 0:
            errors.append("invalid_amount")
        if entry is not None and entry.phone_number and not entry.phone_number.startswith("+254"):
            warnings.append("non_standard_phone_format")
        return ValidationReport("validated" if not errors else "rejected", tuple(errors), tuple(warnings))

    @staticmethod
    def _dedupe_key(order: PurchaseOrder, entry: LedgerEntry) -> tuple[str, str, str]:
        return (
            (order.reference or order.po_number or "").upper(),
            (entry.reference or entry.entry_id or "").upper(),
            str((order.amount or Decimal("0"), entry.amount or Decimal("0"))),
        )

    def match_one(self, order: PurchaseOrder, ledger_entries: Sequence[LedgerEntry]) -> dict[str, Any] | None:
        if order is None:
            return None
        order_validation = self.validate_purchase_order(order)
        if not order_validation.is_valid:
            return {
                "purchase_order": order,
                "ledger_entry": None,
                "score": 0,
                "reasons": ["rejected"],
                "status": "rejected",
                "validation_status": order_validation.status,
                "validation_errors": list(order_validation.errors),
                "confidence": 0.0,
                "duplicate": False,
            }

        best = None
        seen_pairs: set[tuple[str, str, str]] = set()
        for entry in ledger_entries:
            if entry is None:
                continue
            entry_validation = self.validate_ledger_entry(entry)
            if not entry_validation.is_valid:
                continue
            pair = self._dedupe_key(order, entry)
            if pair in seen_pairs:
                continue
            seen_pairs.add(pair)
            score, reasons = self._score(order, entry)
            if score <= 0:
                continue
            candidate = {
                "purchase_order": order,
                "ledger_entry": entry,
                "score": score,
                "reasons": reasons,
                "status": self._classify(score, reasons),
                "confidence": self._confidence(score),
                "validation_status": "validated",
                "validation_errors": [],
                "duplicate": False,
            }
            if best is None or candidate["score"] > best["score"]:
                best = candidate
        if best is None:
            return None
        return best

    def match_many(self, purchase_orders: Sequence[PurchaseOrder], ledger_entries: Sequence[LedgerEntry]) -> list[dict[str, Any]]:
        matches: list[dict[str, Any]] = []
        seen_order_pairs: set[tuple[str, str, str]] = set()
        for order in purchase_orders:
            if order is None:
                continue
            order_validation = self.validate_purchase_order(order)
            if not order_validation.is_valid:
                matches.append(
                    {
                        "purchase_order": order,
                        "ledger_entry": None,
                        "score": 0,
                        "reasons": ["rejected"],
                        "status": "rejected",
                        "validation_status": order_validation.status,
                        "validation_errors": list(order_validation.errors),
                        "confidence": 0.0,
                        "duplicate": False,
                    }
                )
                continue

            best_result = None
            for entry in ledger_entries:
                if entry is None:
                    continue
                if not self.validate_ledger_entry(entry).is_valid:
                    continue
                pair = self._dedupe_key(order, entry)
                if pair in seen_order_pairs:
                    matches.append(
                        {
                            "purchase_order": order,
                            "ledger_entry": entry,
                            "score": 0,
                            "reasons": ["duplicate_record"],
                            "status": "duplicate",
                            "validation_status": "duplicate",
                            "validation_errors": [],
                            "confidence": 0.0,
                            "duplicate": True,
                        }
                    )
                    continue
                score, reasons = self._score(order, entry)
                if score <= 0:
                    continue
                candidate = {
                    "purchase_order": order,
                    "ledger_entry": entry,
                    "score": score,
                    "reasons": reasons,
                    "status": self._classify(score, reasons),
                    "confidence": self._confidence(score),
                    "validation_status": "validated",
                    "validation_errors": [],
                    "duplicate": False,
                }
                if best_result is None or candidate["score"] > best_result["score"]:
                    best_result = candidate
                seen_order_pairs.add(pair)

            if best_result is not None:
                matches.append(best_result)

        return matches

    def persist_matches(self, matches: Sequence[dict[str, Any]]) -> list[PersistedMatchRecord]:
        records: list[PersistedMatchRecord] = []
        for index, match in enumerate(matches, start=1):
            order = match.get("purchase_order")
            entry = match.get("ledger_entry")
            if order is None and entry is None:
                continue
            status = str(match.get("status") or "unmatched").lower()
            if status not in VALID_MATCH_STATUSES:
                status = "unmatched"
            record = PersistedMatchRecord(
                record_id=f"match_{index}_{datetime.now(timezone.utc).strftime('%Y%m%d%H%M%S%f')}",
                purchase_order_id=str(getattr(order, "po_number", "") or getattr(order, "reference", "") or "unknown-po"),
                ledger_entry_id=str(getattr(entry, "entry_id", "") or getattr(entry, "reference", "") or "unknown-entry"),
                status=status,
                confidence=float(match.get("confidence", 0.0) or 0.0),
                score=int(match.get("score", 0) or 0),
                reasons=tuple(match.get("reasons", []) or ()),
                created_at=datetime.now(timezone.utc),
            )
            records.append(record)
        return records

    def _score(self, order: PurchaseOrder, entry: LedgerEntry) -> tuple[int, list[str]]:
        reasons: list[str] = []
        score = 0

        order_ref = (order.reference or order.po_number or "").upper()
        entry_ref = (entry.reference or "").upper()
        if order_ref and entry_ref and order_ref == entry_ref:
            score += 60
            reasons.append("reference")
        elif order_ref and entry_ref and (order_ref in entry_ref or entry_ref in order_ref):
            score += 35
            reasons.append("reference_approx")

        if order.po_number and entry.reference and order.po_number.upper() == entry.reference.upper():
            score += 20
            reasons.append("po_reference")

        if order.amount is not None and entry.amount is not None:
            delta = abs(entry.amount - order.amount)
            if delta == 0:
                score += 35
                reasons.append("amount")
            elif delta <= self.tolerance_amount:
                score += 18
                reasons.append("amount_tolerance")

        if order.phone_number and entry.phone_number and order.phone_number == entry.phone_number:
            score += 15
            reasons.append("phone")

        if order.timestamp and entry.timestamp:
            delta_secs = abs((entry.timestamp - order.timestamp).total_seconds())
            if delta_secs <= self.tolerance_minutes * 60:
                score += 10
                reasons.append("timestamp")

        return score, reasons

    @staticmethod
    def _classify(score: int, reasons: list[str]) -> str:
        if score >= 90:
            return "matched"
        if score >= 60:
            return "partial_match"
        if score >= 30:
            return "manual_review"
        return "unmatched"

    @staticmethod
    def _confidence(score: int) -> float:
        return max(0.0, min(1.0, score / 100.0))


def match_purchase_orders(
    purchase_orders: Sequence[PurchaseOrder],
    ledger_entries: Sequence[LedgerEntry],
    *,
    tolerance_amount: Decimal | str | float = Decimal("0.01"),
    tolerance_minutes: int = 60,
) -> list[dict[str, Any]]:
    return LedgerMatcher(
        tolerance_amount=tolerance_amount,
        tolerance_minutes=tolerance_minutes,
    ).match_many(purchase_orders, ledger_entries)


__all__ = [
    "MatchResult",
    "PersistedMatchRecord",
    "ValidationReport",
    "LedgerMatcher",
    "match_purchase_orders",
]
