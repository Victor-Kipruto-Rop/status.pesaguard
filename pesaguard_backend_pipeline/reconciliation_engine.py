"""
Reconciliation Engine for matching M-Pesa Daraja callbacks to internal ledger records.

Turns raw webhook events into auditable reconciliation outcomes so operational teams
can distinguish between exact matches, partial matches, missing payments, and duplicate callbacks.
"""

from __future__ import annotations

import logging
import time
from bisect import bisect_left, bisect_right
from decimal import Decimal
from datetime import datetime, timezone
from typing import Any, Dict, List, Mapping, Optional, Sequence, Set, Iterable

from event_store import ProcessResult
from normalization import (
    DEFAULT_CURRENCY,
    NormalizationError,
    currency_quantum,
    normalize_amount,
    normalize_currency,
    normalize_identifier,
)
from reconciliation_scoring import score_match

# Import normalization helpers (added in feat/phase1-reconciliation-systematic)
try:
    from .reconciliation_utils import normalize_daraja_event, time_window_match
except ImportError:  # pragma: no cover - fallback for top-level script execution
    from reconciliation_utils import normalize_daraja_event, time_window_match

logger = logging.getLogger("pesaguard.reconciliation_engine")


RECONCILIATION_STATUSES = frozenset({
    "MATCHED", "UNMATCHED", "PARTIAL", "MISMATCH", "DUPLICATE", "PENDING", "EXCEPTION",
})
ENGINE_VERSION = "phase3.3"
MAX_RECONCILIATION_BATCH_RECORDS = 5000


def _format_derived_amount(value: Decimal, currency: str) -> str:
    scale = max(0, -currency_quantum(currency).as_tuple().exponent)
    quantized = value.quantize(currency_quantum(currency))
    if quantized != value:
        raise ValueError("derived reconciliation amount exceeds currency minor-unit precision")
    return f"{quantized:.{scale}f}"


class ReconciliationEngine:
    """Versioned raw-transaction-to-ledger reconciliation pipeline."""

    def __init__(self, *, tolerance_percent: Decimal = Decimal("0.5"), window_seconds: int = 900):
        self.tolerance_percent = Decimal(str(tolerance_percent))
        if not self.tolerance_percent.is_finite() or self.tolerance_percent < 0:
            raise ValueError("tolerance_percent must be a finite, non-negative value")
        self.window_seconds = int(window_seconds)
        if self.window_seconds < 0:
            raise ValueError("window_seconds must be non-negative")

    def reconcile(
        self,
        raw_transaction: Dict[str, Any],
        internal_records: Sequence[Dict[str, Any]],
        *,
        seen_transaction_ids: Optional[Set[str]] = None,
        now: Optional[datetime] = None,
    ) -> Dict[str, Any]:
        started = time.perf_counter()
        now = now or datetime.now(timezone.utc)
        if now.tzinfo is None:
            now = now.replace(tzinfo=timezone.utc)
        else:
            now = now.astimezone(timezone.utc)
        try:
            transaction = self._normalize_transaction(raw_transaction)
            records = [self._normalize_record(record) for record in internal_records]
        except (TypeError, ValueError) as exc:
            return self._result("EXCEPTION", None, [], 0.0, started, now, reason=str(exc))

        transaction_id = transaction.get("transaction_id")
        ledger_tenants = {
            record["tenant_id"] for record in records if record.get("tenant_id")
        }
        if ledger_tenants and (
            not transaction["tenant_id"]
            or ledger_tenants != {transaction["tenant_id"]}
        ):
            return self._result(
                "EXCEPTION",
                None,
                ["tenant_scope_mismatch"],
                0.0,
                started,
                now,
                reason="transaction and ledger records must share an explicit tenant scope",
                transaction=transaction,
            )
        if not transaction_id:
            return self._result("EXCEPTION", None, [], 0.0, started, now, reason="missing transaction id")
        transaction_type = transaction.get("transaction_type")
        if transaction_type in {"refund", "reversal"} and not transaction.get("original_reference"):
            return self._result(
                "EXCEPTION",
                None,
                [f"{transaction_type}_requires_original_reference"],
                0.0,
                started,
                now,
                reason=f"{transaction_type} requires original transaction reference",
            )
        normalized_seen_ids = {
            normalize_identifier(value) for value in (seen_transaction_ids or set()) if str(value).strip()
        }
        if transaction_id in normalized_seen_ids:
            return self._result("DUPLICATE", None, ["duplicate_transaction_id"], 1.0, started, now)

        if transaction_type in {"refund", "reversal"}:
            records = [
                record for record in records
                if record.get("original_reference") == transaction.get("original_reference")
                or record.get("reference") == transaction.get("original_reference")
            ]
            if not records:
                return self._result("UNMATCHED", None, ["original_transaction_missing"], 0.0, started, now)

        if not records:
            return self._result("UNMATCHED", None, ["missing_internal_record"], 0.0, started, now)

        same_reference = [record for record in records if self._reference_matches(transaction, record)]
        if len(same_reference) > 1 and transaction_type not in {"refund", "reversal"}:
            duplicate_ids = self._duplicate_record_ids(same_reference)
            if duplicate_ids:
                return self._result(
                    "DUPLICATE",
                    None,
                    ["duplicate_internal_reference"],
                    1.0,
                    started,
                    now,
                )
            return self._reconcile_reference_group(transaction, same_reference, started, now)
        if len(same_reference) > 1:
            return self._result("DUPLICATE", None, ["duplicate_internal_reference"], 1.0, started, now)

        exact = self._exact_match(transaction, records)
        if exact:
            status = "PENDING" if exact.get("_pending_match") else "MATCHED"
            return self._result(
                status,
                exact,
                ["reference_exact", "amount_exact", "timestamp_within_window"],
                1.0,
                started,
                now,
                transaction=transaction,
            )

        if same_reference:
            candidate = same_reference[0]
            if not self._same_currency(transaction, candidate):
                # Same reference, different currency: the amounts are not
                # comparable at all. Classify explicitly instead of reporting a
                # partial/amount-mismatch verdict computed across currencies.
                logger.warning(
                    "reconciliation currency mismatch",
                    extra={
                        "tenant_id": transaction.get("tenant_id"),
                        "transaction_id": transaction.get("transaction_id"),
                        "transaction_currency": transaction.get("currency"),
                        "record_currency": candidate.get("currency"),
                    },
                )
                return self._result(
                    "EXCEPTION",
                    candidate,
                    ["currency_mismatch"],
                    0.0,
                    started,
                    now,
                    reason=(
                        f"transaction currency {transaction.get('currency')} does not match "
                        f"record currency {candidate.get('currency')}"
                    ),
                    transaction=transaction,
                )
            amount_delta = abs(transaction["amount"] - candidate["amount"])
            if candidate.get("amount", Decimal("0")) < transaction["amount"]:
                status = "PARTIAL"
                rules = ["reference_exact", "partial_amount"]
            elif amount_delta > self._tolerance(
                transaction["amount"], transaction["currency"]
            ):
                status = "MISMATCH"
                rules = ["reference_exact", "amount_mismatch"]
            else:
                status = "PARTIAL"
                rules = ["reference_exact", "amount_within_tolerance"]
            if not self._time_matches(transaction, candidate):
                rules.append("timestamp_mismatch")
                if status == "PARTIAL":
                    status = "MISMATCH"
            score = self._score(transaction, candidate)
            if transaction_type in {"refund", "reversal"}:
                rules.append(f"{transaction_type}_original_reference")
            return self._result(status, candidate, rules, score, started, now, transaction=transaction)

        tolerant = [record for record in records if self._amount_matches(transaction, record) and self._time_matches(transaction, record)]
        if tolerant:
            candidate, ambiguous = self._best_candidate(transaction, tolerant)
            if ambiguous:
                return self._result(
                    "EXCEPTION",
                    None,
                    ["ambiguous_top_scoring_candidates"],
                    self._score(transaction, ambiguous[0]),
                    started,
                    now,
                    reason="multiple ledger records have the same highest match score",
                    transaction=transaction,
                    ambiguous_candidates=ambiguous,
                )
            return self._result(
                "PARTIAL",
                candidate,
                ["amount_within_tolerance", "timestamp_within_window"],
                self._score(transaction, candidate),
                started,
                now,
                transaction=transaction,
            )

        amount_match = [record for record in records if self._amount_matches(transaction, record)]
        if amount_match:
            candidate, ambiguous = self._best_candidate(transaction, amount_match)
            if ambiguous:
                return self._result(
                    "EXCEPTION",
                    None,
                    ["ambiguous_top_scoring_candidates"],
                    self._score(transaction, ambiguous[0]),
                    started,
                    now,
                    reason="multiple ledger records have the same highest match score",
                    transaction=transaction,
                    ambiguous_candidates=ambiguous,
                )
            return self._result(
                "MISMATCH",
                candidate,
                ["amount_match", "timestamp_mismatch_or_reference_mismatch"],
                self._score(transaction, candidate),
                started,
                now,
                transaction=transaction,
            )

        return self._result("UNMATCHED", None, ["no_matching_candidate"], 0.0, started, now)

    def _reconcile_reference_group(
        self,
        transaction: Dict[str, Any],
        records: Sequence[Dict[str, Any]],
        started: float,
        now: datetime,
    ) -> Dict[str, Any]:
        currencies = {record["currency"] for record in records}
        if currencies != {transaction["currency"]}:
            return self._result(
                "EXCEPTION",
                None,
                ["currency_mismatch"],
                0.0,
                started,
                now,
                reason="reference group contains a currency that differs from the payment",
                transaction=transaction,
                matched_records=records,
            )

        ordered_records = sorted(records, key=lambda item: item["internal_ref"])
        ledger_total = sum((record["amount"] for record in ordered_records), Decimal("0"))
        difference = transaction["amount"] - ledger_total
        timestamps_match = all(self._time_matches(transaction, record) for record in ordered_records)
        amounts_match = difference == 0
        pending = any(record["status"] == "pending" for record in ordered_records)

        if not timestamps_match:
            status = "MISMATCH"
            rules = ["reference_exact", "timestamp_mismatch"]
        elif amounts_match:
            status = "PENDING" if pending else "MATCHED"
            rules = [
                "reference_exact",
                "currency_exact",
                "timestamp_within_window",
                "one_to_many_exact_amount",
            ]
        elif difference > 0:
            status = "PARTIAL"
            rules = ["reference_exact", "currency_exact", "partial_group_amount"]
        else:
            status = "MISMATCH"
            rules = ["reference_exact", "currency_exact", "group_amount_exceeds_payment"]

        evidence = self._result(
            status,
            None,
            rules,
            1.0 if amounts_match and timestamps_match else 0.0,
            started,
            now,
            reason=None,
            transaction=transaction,
            matched_records=ordered_records,
        )
        return evidence

    def _apply_many_to_one_matches(
        self,
        transactions: Mapping[str, Dict[str, Any]],
        outcomes: Dict[str, Dict[str, Any]],
        records_by_id: Mapping[str, Dict[str, Any]],
    ) -> Set[str]:
        grouped: Dict[str, List[Dict[str, Any]]] = {}
        for transaction_id, transaction in transactions.items():
            result = outcomes[transaction_id]
            evidence = result["evidence"]
            matched_record = evidence.get("matched_record")
            internal_ref = (
                normalize_identifier(matched_record.get("internal_ref"))
                if isinstance(matched_record, dict)
                else None
            )
            if (
                result["status"] not in {"PARTIAL", "MISMATCH"}
                or "reference_exact" not in evidence.get("matching_rules", [])
                or not internal_ref
                or internal_ref not in records_by_id
                or transaction["transaction_type"] in {"refund", "reversal"}
            ):
                continue
            ledger_record = records_by_id[internal_ref]
            if (
                transaction["reference"] != ledger_record["reference"]
                or transaction["currency"] != ledger_record["currency"]
                or not self._time_matches(transaction, ledger_record)
            ):
                continue
            grouped.setdefault(internal_ref, []).append(transaction)

        matched_record_ids: Set[str] = set()
        for internal_ref, grouped_transactions in grouped.items():
            if len(grouped_transactions) < 2:
                continue
            ledger_record = records_by_id[internal_ref]
            payment_total = sum(
                (transaction["amount"] for transaction in grouped_transactions),
                Decimal("0"),
            )
            transaction_ids = sorted(item["transaction_id"] for item in grouped_transactions)
            currency = ledger_record["currency"]
            amounts = {
                "currency": currency,
                "payment_amount": _format_derived_amount(payment_total, currency),
                "ledger_amount": _format_derived_amount(ledger_record["amount"], currency),
                "difference": _format_derived_amount(
                    payment_total - ledger_record["amount"],
                    currency,
                ),
            }
            if payment_total == ledger_record["amount"]:
                status = "MATCHED"
                rules = [
                    "reference_exact",
                    "currency_exact",
                    "timestamp_within_window",
                    "many_to_one_exact_amount",
                ]
            elif payment_total < ledger_record["amount"]:
                status = "PARTIAL"
                rules = ["reference_exact", "currency_exact", "many_to_one_partial_amount"]
            else:
                status = "MISMATCH"
                rules = ["reference_exact", "currency_exact", "many_to_one_group_exceeds_ledger"]

            for transaction in grouped_transactions:
                result = outcomes[transaction["transaction_id"]]
                evidence = result["evidence"]
                evidence["matching_rules"] = rules
                evidence["match_score"] = 1.0 if status == "MATCHED" else 0.0
                evidence["matched_record"] = ledger_record["record"]
                evidence["matched_internal_refs"] = [internal_ref]
                evidence["group_transaction_ids"] = transaction_ids
                evidence["amounts"] = amounts
                result["status"] = status
            if status == "MATCHED":
                matched_record_ids.add(internal_ref)
        return matched_record_ids

    @staticmethod
    def _duplicate_record_ids(records: Sequence[Dict[str, Any]]) -> bool:
        identifiers = [record["internal_ref"] for record in records]
        return len(identifiers) != len(set(identifiers))

    def reconcile_batch(
        self,
        raw_transactions: Sequence[Dict[str, Any]],
        internal_records: Sequence[Dict[str, Any]],
        *,
        now: Optional[datetime] = None,
    ) -> Dict[str, Any]:
        """Reconcile a bounded batch once per unique payment and ledger row.

        Duplicate payment IDs are reported but not counted twice in control totals.
        Every ledger row can be allocated to at most one exact match; partial,
        mismatched, and exception outcomes do not consume rows.
        """
        batch_time = now or datetime.now(timezone.utc)
        if batch_time.tzinfo is None:
            batch_time = batch_time.replace(tzinfo=timezone.utc)
        else:
            batch_time = batch_time.astimezone(timezone.utc)

        if (
            len(raw_transactions) > MAX_RECONCILIATION_BATCH_RECORDS
            or len(internal_records) > MAX_RECONCILIATION_BATCH_RECORDS
        ):
            raise ValueError(
                f"reconciliation batch is limited to {MAX_RECONCILIATION_BATCH_RECORDS} records per side"
            )

        payment_tenants = {
            str(item.get("tenant_id") or item.get("TenantID") or "").strip()
            for item in raw_transactions
            if isinstance(item, dict) and (item.get("tenant_id") or item.get("TenantID"))
        }
        ledger_tenants = {
            str(item.get("tenant_id") or item.get("TenantID") or "").strip()
            for item in internal_records
            if isinstance(item, dict) and (item.get("tenant_id") or item.get("TenantID"))
        }
        if (
            len(payment_tenants) > 1
            or len(ledger_tenants) > 1
            or (ledger_tenants and payment_tenants != ledger_tenants)
        ):
            raise ValueError("reconciliation batch cannot span tenants")

        transactions = [(self._normalize_transaction(item), item) for item in raw_transactions]
        records = [self._normalize_record(item) for item in internal_records]
        record_ids = [record["internal_ref"] for record in records]
        if len(record_ids) != len(set(record_ids)):
            raise ValueError("ledger batch contains duplicate normalized internal record identifiers")

        transaction_signatures: Dict[str, set[tuple[Any, ...]]] = {}
        for transaction, _ in transactions:
            signature = (
                transaction["reference"],
                transaction["amount"],
                transaction["currency"],
                transaction["timestamp"],
                transaction["phone"],
                transaction["transaction_type"],
                transaction["original_reference"],
            )
            transaction_signatures.setdefault(transaction["transaction_id"], set()).add(signature)
        conflicting_ids = sorted(
            transaction_id
            for transaction_id, signatures in transaction_signatures.items()
            if len(signatures) > 1
        )
        if conflicting_ids:
            raise ValueError(
                "transaction batch reuses an identifier with conflicting financial data: "
                + ", ".join(conflicting_ids)
            )

        transactions.sort(
            key=lambda pair: (
                pair[0]["transaction_id"],
                pair[0]["reference"],
                pair[0]["currency"],
                pair[0]["amount"],
                pair[0]["timestamp"],
                pair[0]["phone"],
            )
        )
        records.sort(key=lambda item: (item["reference"], item["internal_ref"]))
        records_by_id = {item["internal_ref"]: item for item in records}
        reference_index: Dict[str, Set[str]] = {}
        amount_entries: Dict[str, List[tuple[Decimal, str]]] = {}
        for record in records:
            for reference in {record["reference"], record["original_reference"]} - {""}:
                reference_index.setdefault(reference, set()).add(record["internal_ref"])
            amount_entries.setdefault(record["currency"], []).append(
                (record["amount"], record["internal_ref"])
            )
        amount_index = {}
        for currency, entries in amount_entries.items():
            entries.sort()
            amount_index[currency] = (
                [entry[0] for entry in entries],
                [entry[1] for entry in entries],
            )

        available_record_ids = set(record_ids)
        seen_ids: Set[str] = set()
        used_record_ids: Set[str] = set()
        outcomes = []

        for transaction, raw in transactions:
            transaction_id = transaction["transaction_id"]
            reference = (
                transaction["original_reference"]
                if transaction["transaction_type"] in {"refund", "reversal"}
                else transaction["reference"]
            )
            candidate_ids = set(reference_index.get(reference, set()))
            candidate_ids.intersection_update(available_record_ids)
            if not candidate_ids and transaction["currency"] in amount_index:
                amounts, identifiers = amount_index[transaction["currency"]]
                tolerance = self._tolerance(transaction["amount"], transaction["currency"])
                lower = transaction["amount"] - tolerance
                upper = transaction["amount"] + tolerance
                start = bisect_left(amounts, lower)
                end = bisect_right(amounts, upper)
                candidate_ids.update(identifiers[start:end])
            candidate_ids.intersection_update(available_record_ids)
            candidates = [
                records_by_id[internal_ref]["record"]
                for internal_ref in sorted(candidate_ids)
            ]
            result = self.reconcile(
                raw,
                candidates,
                seen_transaction_ids=seen_ids,
                now=batch_time,
            )
            if result["status"] != "DUPLICATE":
                seen_ids.add(transaction_id)
            if result["status"] in {"MATCHED", "PENDING"}:
                matched_ids = set(result["evidence"].get("matched_internal_refs", []))
                used_record_ids.update(matched_ids)
                available_record_ids.difference_update(matched_ids)
            outcomes.append({"transaction_id": transaction_id, "result": result})

        unique_payments = {}
        for transaction, _ in transactions:
            unique_payments.setdefault(transaction["transaction_id"], transaction)

        currencies = sorted(
            {item["currency"] for item in unique_payments.values()}
            | {item["currency"] for item in records}
        )
        outcomes_by_id = {}
        for outcome in outcomes:
            outcomes_by_id.setdefault(outcome["transaction_id"], outcome["result"])
        used_record_ids.update(
            self._apply_many_to_one_matches(unique_payments, outcomes_by_id, records_by_id)
        )
        unmatched_records = [
            item["record"] for item in records if item["internal_ref"] not in used_record_ids
        ]
        control_totals = {}
        for currency in currencies:
            payments = [
                item for item in unique_payments.values() if item["currency"] == currency
            ]
            ledger = [item for item in records if item["currency"] == currency]
            matched_payments = [
                item for item in payments
                if outcomes_by_id[item["transaction_id"]]["status"] in {"MATCHED", "PENDING"}
            ]
            unmatched_payments = [
                item for item in payments
                if outcomes_by_id[item["transaction_id"]]["status"] not in {"MATCHED", "PENDING"}
            ]
            unmatched_ledger = [
                item for item in ledger if item["internal_ref"] not in used_record_ids
            ]
            payment_total = sum((item["amount"] for item in payments), Decimal("0"))
            ledger_total = sum((item["amount"] for item in ledger), Decimal("0"))
            control_totals[currency] = {
                "payment_count": len(payments),
                "payment_total": _format_derived_amount(payment_total, currency),
                "ledger_count": len(ledger),
                "ledger_total": _format_derived_amount(ledger_total, currency),
                "difference": _format_derived_amount(payment_total - ledger_total, currency),
                "matched_payment_total": _format_derived_amount(
                    sum((item["amount"] for item in matched_payments), Decimal("0")),
                    currency,
                ),
                "unmatched_payment_total": _format_derived_amount(
                    sum((item["amount"] for item in unmatched_payments), Decimal("0")),
                    currency,
                ),
                "unmatched_ledger_total": _format_derived_amount(
                    sum((item["amount"] for item in unmatched_ledger), Decimal("0")),
                    currency,
                ),
            }

        return {
            "summary": {
                "payment_event_count": len(transactions),
                "unique_payment_count": len(unique_payments),
                "duplicate_payment_count": len(transactions) - len(unique_payments),
                "matched_payment_count": sum(
                    result["status"] in {"MATCHED", "PENDING"}
                    for result in outcomes_by_id.values()
                ),
                "unmatched_payment_count": sum(
                    result["status"] not in {"MATCHED", "PENDING", "DUPLICATE"}
                    for result in outcomes_by_id.values()
                ),
                "ledger_record_count": len(records),
                "matched_ledger_record_count": len(used_record_ids),
                "unmatched_ledger_record_count": len(unmatched_records),
            },
            "results": outcomes,
            "unmatched_ledger_records": unmatched_records,
            "control_totals": control_totals,
        }

    def _normalize_transaction(self, value: Dict[str, Any]) -> Dict[str, Any]:
        if not isinstance(value, dict):
            raise ValueError("transaction must be an object")
        currency = _record_currency(value)
        amount = self._amount(value.get("TransAmount", value.get("amount")), currency)
        timestamp = _parse_event_time(value.get("TransTime", value.get("timestamp")))
        if timestamp is None:
            raise ValueError("transaction timestamp is required and must be valid")
        transaction_id = normalize_identifier(value.get("TransID") or value.get("trans_id"))
        reference = normalize_identifier(
            value.get("BillRefNumber") or value.get("reference") or value.get("TransID")
        )
        raw_original_reference = (
            value.get("OriginalTransID")
            or value.get("OriginalTransactionID")
            or value.get("original_transaction_id")
            or value.get("original_reference")
        )
        return {
            "transaction_id": transaction_id,
            "tenant_id": str(value.get("tenant_id") or value.get("TenantID") or "").strip(),
            "reference": reference,
            "amount": amount,
            "currency": currency,
            "timestamp": timestamp,
            "phone": str(value.get("MSISDN") or value.get("phone_number") or "").strip(),
            "transaction_type": str(value.get("TransactionType") or value.get("transaction_type") or "payment").strip().lower(),
            "original_reference": normalize_identifier(raw_original_reference) if raw_original_reference else "",
        }

    def _normalize_record(self, value: Dict[str, Any]) -> Dict[str, Any]:
        if not isinstance(value, dict):
            raise ValueError("internal record must be an object")
        currency = _record_currency(value)
        amount = self._amount(value.get("amount", value.get("TransAmount")), currency)
        internal_ref = normalize_identifier(value.get("internal_ref"))
        raw_reference = value.get("reference") or value.get("merchant_ref") or value.get("internal_ref")
        reference = normalize_identifier(raw_reference)
        raw_timestamp = value.get("timestamp") or value.get("created_at") or value.get("synced_at")
        timestamp = _parse_record_time(raw_timestamp)
        if raw_timestamp not in (None, "") and timestamp is None:
            raise ValueError("ledger timestamp must be valid")
        raw_original_reference = (
            value.get("original_transaction_id")
            or value.get("original_reference")
            or value.get("original_ref")
        )
        return {
            "internal_ref": internal_ref,
            "reference": reference,
            "tenant_id": str(value.get("tenant_id") or value.get("TenantID") or "").strip(),
            "original_reference": normalize_identifier(raw_original_reference) if raw_original_reference else "",
            "amount": amount,
            "currency": currency,
            "timestamp": timestamp,
            "phone": str(value.get("phone_number") or value.get("MSISDN") or value.get("msisdn") or "").strip(),
            "status": str(value.get("status") or "").strip().lower(),
            "record": value,
        }

    @staticmethod
    def _amount(value: Any, currency: str = DEFAULT_CURRENCY) -> Decimal:
        try:
            return Decimal(normalize_amount(value, currency=currency))
        except NormalizationError as exc:
            raise ValueError(str(exc)) from exc

    def _tolerance(self, amount: Decimal, currency: str) -> Decimal:
        return max(
            currency_quantum(currency),
            abs(amount) * self.tolerance_percent / Decimal("100"),
        )

    def _same_currency(self, transaction: Dict[str, Any], record: Dict[str, Any]) -> bool:
        """Amounts are only comparable within one currency (Rule #11).

        A KES callback and a USD ledger line that happen to share a reference and
        a number must never be declared matched: the difference is surfaced as an
        explicit ``currency_mismatch`` exception instead of a silent match.
        """
        return transaction.get("currency") == record.get("currency")

    def _time_matches(self, transaction: Dict[str, Any], record: Dict[str, Any]) -> bool:
        if transaction["timestamp"] is None or record["timestamp"] is None:
            return False
        return abs((transaction["timestamp"] - record["timestamp"]).total_seconds()) <= self.window_seconds

    def _amount_matches(self, transaction: Dict[str, Any], record: Dict[str, Any]) -> bool:
        if not self._same_currency(transaction, record):
            return False
        return abs(transaction["amount"] - record["amount"]) <= self._tolerance(
            transaction["amount"], transaction["currency"]
        )

    @staticmethod
    def _reference_matches(transaction: Dict[str, Any], record: Dict[str, Any]) -> bool:
        if transaction.get("transaction_type") in {"refund", "reversal"}:
            return bool(transaction.get("original_reference")) and (
                record.get("original_reference") == transaction.get("original_reference")
                or record.get("reference") == transaction.get("original_reference")
            )
        return record.get("reference") == transaction.get("reference")

    def _exact_match(self, transaction: Dict[str, Any], records: Sequence[Dict[str, Any]]) -> Optional[Dict[str, Any]]:
        for record in records:
            if (
                self._reference_matches(transaction, record)
                and record["amount"] == transaction["amount"]
                and self._same_currency(transaction, record)
                and self._time_matches(transaction, record)
                and (not transaction["phone"] or not record["phone"] or transaction["phone"] == record["phone"])
            ):
                if record.get("status") == "pending":
                    return {**record, "_pending_match": True}
                return record
        return None

    def _score(self, transaction: Dict[str, Any], record: Dict[str, Any]) -> float:
        score = Decimal("0")
        score += Decimal("0.45") if transaction["reference"] and transaction["reference"] == record["reference"] else Decimal("0")
        score += Decimal("0.30") if transaction["amount"] == record["amount"] else Decimal("0.15") if self._amount_matches(transaction, record) else Decimal("0")
        score += Decimal("0.15") if self._time_matches(transaction, record) else Decimal("0")
        score += Decimal("0.10") if transaction["phone"] and transaction["phone"] == record["phone"] else Decimal("0")
        return float(min(Decimal("1"), score))

    def _best_candidate(
        self,
        transaction: Dict[str, Any],
        records: Sequence[Dict[str, Any]],
    ) -> tuple[Optional[Dict[str, Any]], List[Dict[str, Any]]]:
        """Return a unique top-scoring candidate and any tied alternatives.

        Multiple internal records can tie on match score (e.g. two records with the
        same amount, time and reference). Plain ``max`` would then return whichever
        appears first in the input list, so reordering the records would change which
        transaction a callback is reconciled against -- a non-deterministic,
        non-explainable financial decision (Rule #26/#27).

        The secondary key only orders ambiguity evidence; it never selects a candidate.
        Candidate identifiers are listed in stable string order; only a unique
        highest score can be selected automatically.
        """
        ranked = sorted(
            records,
            key=lambda record: (-self._score(transaction, record), str(record.get("internal_ref") or "")),
        )
        highest_score = self._score(transaction, ranked[0])
        tied = [record for record in ranked if self._score(transaction, record) == highest_score]
        if len(tied) > 1:
            return None, tied
        return ranked[0], []

    def _result(
        self,
        status: str,
        record: Optional[Dict[str, Any]],
        rules: List[str],
        score: float,
        started: float,
        now: datetime,
        *,
        reason: Optional[str] = None,
        transaction: Optional[Mapping[str, Any]] = None,
        matched_records: Optional[Sequence[Dict[str, Any]]] = None,
        ambiguous_candidates: Optional[Sequence[Dict[str, Any]]] = None,
    ) -> Dict[str, Any]:
        rows = list(matched_records or ([record] if record is not None else []))
        raw_rows = [row.get("record", row) for row in rows]
        evidence = {
            "matched_record": record.get("record", record) if record else None,
            "matching_rules": rules,
            "match_score": score,
            "match_timestamp": now.isoformat(),
            "engine_version": ENGINE_VERSION,
            "rule_version": ENGINE_VERSION,
        }
        if ambiguous_candidates:
            evidence["ambiguous_candidate_refs"] = sorted(
                str(candidate.get("internal_ref") or "") for candidate in ambiguous_candidates
            )
        if transaction is not None:
            evidence["transaction_currency"] = transaction.get("currency")
        if len(raw_rows) > 1:
            evidence["matched_records"] = raw_rows
        if rows:
            evidence["matched_internal_refs"] = sorted(row["internal_ref"] for row in rows)
        if transaction is not None and rows and all(
            row["currency"] == transaction.get("currency") for row in rows
        ):
            ledger_total = sum((row["amount"] for row in rows), Decimal("0"))
            payment_amount = transaction["amount"]
            currency = str(transaction["currency"])
            evidence["amounts"] = {
                "currency": currency,
                "payment_amount": _format_derived_amount(payment_amount, currency),
                "ledger_amount": _format_derived_amount(ledger_total, currency),
                "difference": _format_derived_amount(payment_amount - ledger_total, currency),
            }
        result = {
            "status": status,
            "evidence": evidence,
            "processing_latency_ms": (time.perf_counter() - started) * 1000,
        }
        if reason:
            result["reason"] = reason
        return result


def measure_reconciliation(results: Iterable[Dict[str, Any]], expected_statuses: Iterable[str]) -> Dict[str, float]:
    """Calculate precision, recall, error counts, latency, and throughput."""
    started = time.perf_counter()
    actual = list(results)
    expected = list(expected_statuses)
    correct = sum(result.get("status") == expected_status for result, expected_status in zip(actual, expected))
    positives = sum(status == "MATCHED" for status in expected)
    predicted_positives = sum(result.get("status") == "MATCHED" for result in actual)
    true_positives = sum(result.get("status") == expected_status == "MATCHED" for result, expected_status in zip(actual, expected))
    false_positives = predicted_positives - true_positives
    false_negatives = positives - true_positives
    return {
        "precision": true_positives / predicted_positives if predicted_positives else 1.0,
        "recall": true_positives / positives if positives else 1.0,
        "false_positives": float(false_positives),
        "false_negatives": float(false_negatives),
        "known_cases": float(len(expected)),
        "correct_cases": float(correct),
        "processing_latency_ms": sum(float(result.get("processing_latency_ms", 0)) for result in actual) / max(len(actual), 1),
        "throughput_per_second": len(actual) / max(time.perf_counter() - started, 0.000001),
    }


def record_ground_truth(
    session: Any,
    *,
    tenant_id: str,
    transaction_id: str,
    expected_status: str,
    approved_by: str,
    approval_reference: str,
    actual_status: Optional[str] = None,
    validated_by: Optional[str] = None,
    notes: Optional[str] = None,
) -> Any:
    """Create or validate one certification ground-truth case transactionally."""
    from models import ReconciliationGroundTruth

    if not approved_by.strip() or not approval_reference.strip():
        raise ValueError("approved_by and approval_reference are required")
    if expected_status not in RECONCILIATION_STATUSES:
        raise ValueError(f"unsupported expected reconciliation status: {expected_status}")
    if actual_status is not None and actual_status not in RECONCILIATION_STATUSES:
        raise ValueError(f"unsupported actual reconciliation status: {actual_status}")
    row = session.query(ReconciliationGroundTruth).filter_by(
        tenant_id=tenant_id,
        transaction_id=transaction_id,
    ).one_or_none()
    if row is None:
        row = ReconciliationGroundTruth(
            id=f"truth_{tenant_id}_{transaction_id}",
            tenant_id=tenant_id,
            transaction_id=transaction_id,
            expected_status=expected_status,
            approved_by=approved_by.strip(),
            approval_reference=approval_reference.strip(),
            approved_at=datetime.now(timezone.utc),
        )
        session.add(row)
    elif row.expected_status != expected_status:
        raise ValueError("ground-truth status cannot be changed after registration")
    elif row.approved_by != approved_by.strip() or row.approval_reference != approval_reference.strip():
        raise ValueError("ground-truth approval metadata cannot be changed")
    row.actual_status = actual_status
    row.validated_by = validated_by
    row.notes = notes
    row.validated_at = datetime.now(timezone.utc) if actual_status is not None else None
    return row


def evaluate_transaction(
    event: Dict[str, Any],
    internal_records: Sequence[Dict[str, Any]],
    seen_trans_ids: Set[str],
    window_minutes: int = 15,
    tenant_settings: Optional[Dict[str, Any]] = None,
) -> Dict[str, Any]:
    """Evaluate one M-Pesa event against a set of internal records.

    Args:
        event: Daraja callback payload dict
        internal_records: List of internal order/payment dicts
        seen_trans_ids: Set of recently observed transaction IDs
        window_minutes: Default matching time window in minutes
        tenant_settings: Optional tenant-specific settings dict for custom rules

    Returns:
        Structured evaluation outcome dict
    """
    original_amount = event.get("TransAmount")
    if original_amount is None:
        original_amount = event.get("amount")

    # Normalize incoming event into canonical keys without discarding original keys.
    # This preserves backwards compatibility while centralizing parsing/format logic.
    try:
        norm = normalize_daraja_event(event, tenant_id=str(tenant_settings.get("tenant_id")) if tenant_settings else "default")
    except (NormalizationError, TypeError, ValueError):
        norm = {}
    # Merge normalized canonical keys into the event for the rest of the engine to use.
    merged_event = dict(event or {})
    merged_event.update(norm or {})
    if original_amount is not None:
        merged_event["TransAmount"] = original_amount
        merged_event["amount"] = original_amount
    event = merged_event

    trans_id = str(event.get("TransID") or event.get("trans_id") or "unknown").strip()
    duplicate = trans_id in seen_trans_ids

    anomalies: List[str] = []
    if duplicate:
        anomalies.append("duplicate_transaction_id")

    try:
        event_currency = _record_currency(event)
    except ValueError:
        event_currency = None
        anomalies.append("invalid_currency")

    event_time = _parse_event_time(event.get("TransTime"))
    if event_time:
        latency = (datetime.now(timezone.utc) - event_time).total_seconds()
        if latency > 3600:
            anomalies.append("late_arriving_event")

    amount = (
        _coerce_amount(event.get("TransAmount") or event.get("amount"), event_currency)
        if event_currency is not None
        else None
    )
    if amount is None or amount <= 0:
        anomalies.append("invalid_or_zero_amount")

    # Resolve tenant config overrides
    reconciliation_cfg = (tenant_settings or {}).get("reconciliation", {}) if tenant_settings else {}
    tolerance_percent = Decimal(str(reconciliation_cfg.get("tolerance_percent", "0.5")))
    allow_partial = bool(reconciliation_cfg.get("allow_partial", True))
    
    if reconciliation_cfg.get("window_minutes") is not None:
        try:
            window_minutes = int(reconciliation_cfg["window_minutes"])
        except (TypeError, ValueError):
            pass

    if not internal_records:
        return {
            "trans_id": trans_id,
            "status": "missing_payment",
            "severity": "critical",
            "duplicate": duplicate,
            "anomalies": anomalies,
            "match": {"match_type": "none", "reason": "no_internal_records"},
        }

    best_match = _find_best_match(
        event,
        internal_records,
        window_minutes=window_minutes,
        tolerance_percent=tolerance_percent,
        allow_partial=allow_partial,
    )

    if best_match is None:
        return {
            "trans_id": trans_id,
            "status": "missing_payment",
            "severity": "critical",
            "duplicate": duplicate,
            "anomalies": anomalies,
            "match": {"match_type": "none", "reason": "no_matching_record"},
        }

    if best_match["match_type"] in {"exact", "fuzzy_exact"}:
        return {
            "trans_id": trans_id,
            "status": "matched",
            "severity": "info",
            "duplicate": duplicate,
            "anomalies": anomalies,
            "match": best_match,
        }

    return {
        "trans_id": trans_id,
        "status": "needs_review",
        "severity": "warning",
        "duplicate": duplicate,
        "anomalies": anomalies,
        "match": best_match,
    }


def _find_best_match(
    event: Dict[str, Any],
    internal_records: Sequence[Dict[str, Any]],
    window_minutes: int = 15,
    tolerance_percent: Decimal = Decimal("0.5"),
    allow_partial: bool = True,
) -> Optional[Dict[str, Any]]:
    """Match callback event against candidate internal records."""
    try:
        currency = _record_currency(event)
    except ValueError:
        return None
    amount = _coerce_amount(event.get("TransAmount") or event.get("amount"), currency)
    if amount is None or amount <= 0:
        return None

    phone = str(event.get("MSISDN") or event.get("phone_number") or "").strip()
    event_time = _parse_event_time(event.get("TransTime"))
    allowed_delta = max(
        currency_quantum(currency),
        abs(amount) * (tolerance_percent / Decimal("100")),
    )

    candidates = []
    for record in internal_records:
        record_time = _parse_record_time(record.get("timestamp") or record.get("synced_at") or record.get("created_at"))
        
        latency = 0
        if record_time is not None and event_time is not None:
            latency = int(abs((event_time - record_time).total_seconds()))
            if latency > window_minutes * 60:
                continue

        try:
            record_currency = _record_currency(record)
        except ValueError:
            continue
        if record_currency != currency:
            continue

        record_amount = _coerce_amount(record.get("amount"), record_currency)
        if record_amount is None:
            continue

        amt_diff = abs(record_amount - amount)
        if amt_diff > allowed_delta:
            continue

        record_phone = str(record.get("phone_number") or record.get("msisdn") or "").strip()
        phone_matches = record_phone == phone and len(phone) > 0

        if phone_matches and amt_diff == 0:
            match_type = "exact"
        elif phone_matches and amt_diff <= allowed_delta:
            match_type = "fuzzy_exact"
        elif not phone_matches and allow_partial:
            match_type = "partial_fuzzy" if amt_diff <= allowed_delta else "partial"
        else:
            continue

        candidate_score, candidate_reasons = score_match(event, record)
        candidates.append({
            "match_type": match_type,
            "internal_ref": record.get("internal_ref"),
            "record": record,
            "latency_seconds": latency,
            "amount_diff": amt_diff,
            "score": candidate_score,
            "reasons": candidate_reasons,
        })

    if not candidates:
        return None

    priority = {"exact": 0, "fuzzy_exact": 1, "partial_fuzzy": 2, "partial": 3}
    candidates.sort(
        key=lambda item: (
            -item.get("score", 0.0),
            priority.get(item["match_type"], 99),
            item["latency_seconds"],
            item["amount_diff"],
        )
    )
    highest_score = candidates[0]["score"]
    tied_candidates = [candidate for candidate in candidates if candidate["score"] == highest_score]
    if len(tied_candidates) > 1:
        return {
            "match_type": "ambiguous",
            "score": highest_score,
            "reason": "multiple records have the same highest match score",
            "ambiguous_candidate_refs": sorted(
                str(candidate.get("internal_ref") or "") for candidate in tied_candidates
            ),
            "candidate_evidence": [
                {
                    "internal_ref": candidate.get("internal_ref"),
                    "match_type": candidate["match_type"],
                    "score": candidate["score"],
                    "reasons": candidate["reasons"],
                }
                for candidate in tied_candidates
            ],
        }
    return candidates[0]


def _coerce_amount(value: Any, currency: str = DEFAULT_CURRENCY) -> Optional[Decimal]:
    """Safely parse monetary values as fixed-precision decimals."""
    if value is None:
        return None
    try:
        return Decimal(normalize_amount(value, currency=currency))
    except NormalizationError:
        return None


def _record_currency(value: Mapping[str, Any]) -> str:
    """Resolve the currency of one side of a reconciliation comparison.

    An explicitly invalid currency code is an exception, never a silent default:
    defaulting it would let a mismatched pair compare as if it were the tenant's
    base currency. An absent currency falls back to the platform default, which
    matches how ingestion stamps unlabelled provider payloads (Rule #11).
    """
    for key in ("Currency", "currency"):
        if key in value:
            if value[key] is None or not str(value[key]).strip():
                raise ValueError("currency must not be empty when supplied")
            try:
                return normalize_currency(value[key], default=DEFAULT_CURRENCY)
            except NormalizationError as exc:
                raise ValueError(str(exc)) from exc
    return DEFAULT_CURRENCY


def _parse_event_time(value: Any) -> Optional[datetime]:
    """Parse M-Pesa 14-digit Daraja timestamp strings or ISO timestamps into UTC datetimes."""
    if not value:
        return None

    if isinstance(value, datetime):
        if value.tzinfo is None:
            return value.replace(tzinfo=timezone.utc)
        return value.astimezone(timezone.utc)

    text = str(value).strip()
    if len(text) == 14 and text.isdigit():
        try:
            return datetime.strptime(text, "%Y%m%d%H%M%S").replace(tzinfo=timezone.utc)
        except ValueError:
            return None

    if text.endswith("Z"):
        text = text[:-1] + "+00:00"

    try:
        dt = datetime.fromisoformat(text)
        if dt.tzinfo is None:
            return dt.replace(tzinfo=timezone.utc)
        return dt.astimezone(timezone.utc)
    except ValueError:
        return None


def _parse_record_time(value: Any) -> Optional[datetime]:
    return _parse_event_time(value)


def reconcile_with_idempotency(
    event: Dict[str, Any],
    internal_records: Sequence[Dict[str, Any]],
    event_store: Any,
    discrepancy_dao: Any,
    session: Any,
    tenant_id: Optional[str] = None,
    tenant_settings: Optional[Dict[str, Any]] = None,
    window_minutes: int = 15,
    source_ip: Optional[str] = None,
    signature_verified: bool = False,
) -> Dict[str, Any]:
    """Atomically evaluate and record reconciliation outcomes inside a single database transaction.

    Ensures that both the idempotency ledger write and the Discrepancy write commit
    together or roll back entirely.
    """
    trans_id = str(event.get("TransID") or event.get("trans_id") or "unknown").strip()
    account_id = str(event.get("provider_account_id") or event.get("BusinessShortCode") or event.get("business_short_code") or "legacy-default")

    # Pre-flight duplicate check optimization
    if event_store and event_store.already_processed(trans_id, tenant_id=tenant_id, provider_account=account_id):
        logger.info("Idempotency: duplicate trans_id=%s detected prior to evaluation, skipping", trans_id)
        return {
            "trans_id": trans_id,
            "status": "duplicate_ignored",
            "severity": "info",
            "anomalies": ["duplicate_transaction_id"],
        }

    seen_trans_ids: Set[str] = set()
    evaluation = evaluate_transaction(
        event,
        internal_records,
        seen_trans_ids,
        window_minutes=window_minutes,
        tenant_settings=tenant_settings,
    )
    evaluation["tenant_id"] = tenant_id or "default"
    evaluation["event"] = event

    try:
        result = ProcessResult.STORED
        if event_store:
            result = event_store.mark_processed_in_session(
                session,
                event,
                tenant_id=tenant_id,
                source_ip=source_ip,
                signature_verified=signature_verified,
            )

        if result == ProcessResult.DUPLICATE:
            session.rollback()
            logger.info("Duplicate trans_id=%s caught during idempotency session flush, skipping", trans_id)
            return {
                "trans_id": trans_id,
                "status": "duplicate_ignored",
                "severity": "info",
                "anomalies": ["duplicate_transaction_id"],
            }

        if result in {ProcessResult.ERROR, ProcessResult.CONFLICT}:
            session.rollback()
            raise RuntimeError(f"mark_processed_in_session returned {result.value} for trans_id={trans_id}")

        # Record discrepancy for non-matched or anomalous transactions
        if evaluation.get("status") in {"needs_review", "missing_payment"} or evaluation.get("anomalies"):
            if discrepancy_dao:
                disc_id = f"{trans_id}-{evaluation.get('status', 'unknown')}"
                discrepancy_dao.save_discrepancy(
                    session=session,
                    id=disc_id,
                    trans_id=trans_id,
                    tenant_id=tenant_id,
                    anomaly_type=evaluation.get("status", "unknown"),
                    severity=evaluation.get("severity", "warning"),
                    details=evaluation,
                )

        session.commit()
        evaluation["duplicate"] = False
        return evaluation

    except Exception as exc:
        logger.exception("Error during atomic reconciliation for trans_id=%s — rolling back: %s", trans_id, exc)
        session.rollback()
        raise
