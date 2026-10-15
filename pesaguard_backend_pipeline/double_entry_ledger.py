"""Double-entry ledger service (Modern Treasury-grade guarantees, PesaGuard rules).

Posting rules enforced here, not just at the DB layer:
  * Balanced per currency: sum(debits) == sum(credits) for every currency.
  * Single-ledger, single-tenant: every entry's account lives in the same
    ledger and tenant as the transaction (Rule #11).
  * Atomic: entries all-commit or all-fail in one DB transaction (Rule #8).
  * Idempotent: (tenant, ledger, external_id) retries return the original
    posted row; concurrent double-posts collapse on the unique constraint
    (Rule #9).
  * Immutable once posted: only pending -> posted | voided transitions exist.
  * Optimistic concurrency: balance-dependent writes present the account
    lock_version they read; mismatch is a 409-style LedgerConflictError.
  * Minor-unit integers only: amounts are positive ints in the currency's
    smallest unit; Decimal inputs are quantized exactly or rejected, never
    float-rounded (Rule #7).
"""

from __future__ import annotations

import logging
from dataclasses import dataclass
from datetime import datetime, timezone
from decimal import Decimal
from typing import Any, Mapping, Optional, Sequence
from uuid import uuid4

from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from models import Ledger, LedgerAccount, LedgerEntry, LedgerTransaction
from normalization import CURRENCY_MINOR_UNITS, NormalizationError, normalize_currency

logger = logging.getLogger("pesaguard.double_entry_ledger")


class LedgerError(ValueError):
    """Base class for double-entry ledger failures."""


class LedgerUnbalancedError(LedgerError):
    """Entries do not balance per currency."""


class LedgerConflictError(LedgerError):
    """Idempotent replay mismatch or optimistic-lock version conflict."""


class LedgerStateError(LedgerError):
    """Illegal lifecycle transition (e.g. posting a voided transaction)."""


class LedgerNotFoundError(LedgerError):
    """Referenced ledger, account, or transaction does not exist in scope."""


@dataclass(frozen=True)
class LedgerEntryInput:
    account_code: str
    direction: str  # 'debit' | 'credit'
    amount_minor: int
    currency: str = "KES"
    memo: Optional[str] = None


def to_minor_units(amount: Any, currency: Any) -> int:
    """Convert a Decimal/str/int amount to minor-unit int, exactly or reject."""
    code = normalize_currency(currency)
    scale = CURRENCY_MINOR_UNITS[code]
    quantum = Decimal(1).scaleb(-scale)
    try:
        value = Decimal(str(amount))
    except Exception as exc:
        raise LedgerError(f"invalid ledger amount {amount!r}: {exc}") from exc
    if not value.is_finite() or value <= 0:
        raise LedgerError(f"ledger amount must be a positive finite value, got {amount!r}")
    quantized = value.quantize(quantum)
    if quantized != value:
        raise LedgerError(
            f"ledger amount {amount!r} exceeds {code} minor-unit precision ({scale}dp)"
        )
    minor = int(quantized * (10 ** scale))
    if minor <= 0:
        raise LedgerError(f"ledger amount {amount!r} rounds to zero minor units")
    return minor


def _utcnow() -> datetime:
    return datetime.now(timezone.utc)


def _validate_entries(entries: Sequence[LedgerEntryInput]) -> None:
    if len(entries) < 2:
        raise LedgerUnbalancedError("a ledger transaction requires at least two entries")
    totals: dict[str, int] = {}
    for entry in entries:
        direction = str(entry.direction or "").lower()
        if direction not in {"debit", "credit"}:
            raise LedgerError(f"entry direction must be 'debit' or 'credit', got {entry.direction!r}")
        if not str(entry.account_code or "").strip():
            raise LedgerError("every ledger entry must reference an account code")
        if not isinstance(entry.amount_minor, int) or entry.amount_minor <= 0:
            raise LedgerError(f"entry amount_minor must be a positive int, got {entry.amount_minor!r}")
        code = normalize_currency(entry.currency)
        signed = entry.amount_minor if direction == "debit" else -entry.amount_minor
        totals[code] = totals.get(code, 0) + signed
    imbalanced = {code: total for code, total in totals.items() if total != 0}
    if imbalanced:
        raise LedgerUnbalancedError(
            "entries do not balance per currency: "
            + ", ".join(f"{code} net {net}" for code, net in sorted(imbalanced.items()))
        )


def _get_ledger(session: Session, tenant_id: str, ledger_id: str) -> Ledger:
    ledger = (
        session.query(Ledger)
        .filter(Ledger.tenant_id == tenant_id, Ledger.id == ledger_id)
        .one_or_none()
    )
    if ledger is None:
        raise LedgerNotFoundError(f"ledger {ledger_id!r} not found for tenant {tenant_id}")
    if ledger.status != "active":
        raise LedgerStateError(f"ledger {ledger_id!r} is not active")
    return ledger


def _get_accounts(
    session: Session, tenant_id: str, ledger_id: str, codes: Sequence[str]
) -> dict[str, LedgerAccount]:
    rows = (
        session.query(LedgerAccount)
        .filter(
            LedgerAccount.tenant_id == tenant_id,
            LedgerAccount.ledger_id == ledger_id,
            LedgerAccount.code.in_(list(codes)),
        )
        .with_for_update()
        .all()
    )
    by_code = {row.code: row for row in rows}
    missing = [code for code in codes if code not in by_code]
    if missing:
        raise LedgerNotFoundError(f"ledger accounts not found in scope: {sorted(missing)}")
    for code, account in by_code.items():
        if account.status != "active":
            raise LedgerStateError(f"ledger account {code!r} is {account.status}")
    return by_code


def post_transaction(
    session: Session,
    *,
    tenant_id: str,
    ledger_id: str,
    external_id: str,
    entries: Sequence[LedgerEntryInput],
    description: Optional[str] = None,
    metadata: Optional[Mapping[str, Any]] = None,
    effective_at: Optional[datetime] = None,
    expected_lock_versions: Optional[Mapping[str, int]] = None,
) -> LedgerTransaction:
    """Post a balanced transaction atomically; retries replay idempotently.

    ``expected_lock_versions`` maps account code -> lock_version the caller
    read. Any mismatch raises LedgerConflictError before any write, so
    balance-dependent postings serialize instead of silently overwriting.
    """
    tenant_id = str(tenant_id or "").strip()
    if not tenant_id:
        raise LedgerError("tenant_id is required")
    external_id = str(external_id or "").strip()
    if not external_id or len(external_id) > 180:
        raise LedgerError("external_id is required (max 180 chars)")
    entry_list = list(entries)
    _validate_entries(entry_list)

    ledger = _get_ledger(session, tenant_id, ledger_id)
    codes = [str(e.account_code).strip() for e in entry_list]
    accounts = _get_accounts(session, tenant_id, ledger.id, codes)

    if expected_lock_versions:
        for code, expected in expected_lock_versions.items():
            account = accounts.get(str(code).strip())
            if account is None:
                raise LedgerNotFoundError(f"lock-version check on unknown account {code!r}")
            session.refresh(account)
            if account.lock_version != int(expected):
                raise LedgerConflictError(
                    f"account {code!r} changed since read "
                    f"(expected lock_version={expected}, current={account.lock_version})"
                )

    existing = (
        session.query(LedgerTransaction)
        .filter(
            LedgerTransaction.tenant_id == tenant_id,
            LedgerTransaction.ledger_id == ledger.id,
            LedgerTransaction.external_id == external_id,
        )
        .one_or_none()
    )
    if existing is not None:
        if existing.status != "posted":
            raise LedgerConflictError(
                f"external_id {external_id!r} already exists with status {existing.status}"
            )
        return existing

    now = _utcnow()
    transaction = LedgerTransaction(
        tenant_id=tenant_id,
        ledger_id=ledger.id,
        external_id=external_id,
        description=description,
        status="posted",
        effective_at=effective_at or now,
        posted_at=now,
        ledger_metadata=dict(metadata or {}),
    )
    session.add(transaction)
    session.flush()
    for entry in entry_list:
        account = accounts[str(entry.account_code).strip()]
        session.add(
            LedgerEntry(
                tenant_id=tenant_id,
                ledger_id=ledger.id,
                ledger_transaction_id=transaction.id,
                ledger_account_id=account.id,
                direction=str(entry.direction).lower(),
                amount_minor=int(entry.amount_minor),
                currency=normalize_currency(entry.currency),
                memo=entry.memo,
            )
        )
        account.lock_version = account.lock_version + 1
    try:
        session.flush()
    except IntegrityError as exc:
        session.rollback()
        replayed = (
            session.query(LedgerTransaction)
            .filter(
                LedgerTransaction.tenant_id == tenant_id,
                LedgerTransaction.ledger_id == ledger.id,
                LedgerTransaction.external_id == external_id,
            )
            .one_or_none()
        )
        if replayed is not None and replayed.status == "posted":
            return replayed
        raise LedgerConflictError(f"concurrent post for external_id {external_id!r}") from exc
    logger.info(
        "Ledger transaction posted: tenant=%s ledger=%s external_id=%s entries=%d",
        tenant_id,
        ledger.id,
        external_id,
        len(entry_list),
    )
    return transaction


def void_pending_transaction(session: Session, *, tenant_id: str, transaction_id: str) -> LedgerTransaction:
    """Void a pending transaction. Posted rows are immutable (Rule #35)."""
    transaction = (
        session.query(LedgerTransaction)
        .filter(
            LedgerTransaction.tenant_id == str(tenant_id or "").strip(),
            LedgerTransaction.id == transaction_id,
        )
        .one_or_none()
    )
    if transaction is None:
        raise LedgerNotFoundError(f"ledger transaction {transaction_id!r} not found")
    if transaction.status == "posted":
        raise LedgerStateError("posted ledger transactions are immutable and cannot be voided")
    if transaction.status == "voided":
        return transaction
    transaction.status = "voided"
    session.flush()
    logger.info("Ledger transaction voided: tenant=%s id=%s", tenant_id, transaction_id)
    return transaction


def account_balance(
    session: Session, *, tenant_id: str, ledger_id: str, account_code: str
) -> dict[str, Any]:
    """Derive an account balance from posted entries only (never stored)."""
    ledger = _get_ledger(session, str(tenant_id or "").strip(), ledger_id)
    account = (
        session.query(LedgerAccount)
        .filter(
            LedgerAccount.tenant_id == ledger.tenant_id,
            LedgerAccount.ledger_id == ledger.id,
            LedgerAccount.code == str(account_code or "").strip(),
        )
        .one_or_none()
    )
    if account is None:
        raise LedgerNotFoundError(f"ledger account {account_code!r} not found")
    rows = (
        session.query(LedgerEntry.direction, LedgerEntry.amount_minor, LedgerEntry.currency)
        .join(
            LedgerTransaction,
            (LedgerTransaction.id == LedgerEntry.ledger_transaction_id)
            & (LedgerTransaction.tenant_id == LedgerEntry.tenant_id),
        )
        .filter(
            LedgerEntry.tenant_id == ledger.tenant_id,
            LedgerEntry.ledger_account_id == account.id,
            LedgerTransaction.status == "posted",
        )
        .all()
    )
    per_currency: dict[str, int] = {}
    for direction, amount_minor, currency in rows:
        signed = int(amount_minor) if direction == "debit" else -int(amount_minor)
        per_currency[currency] = per_currency.get(currency, 0) + signed
    if account.normal_balance == "credit":
        per_currency = {code: -net for code, net in per_currency.items()}
    return {
        "ledger_id": ledger.id,
        "account_id": account.id,
        "account_code": account.code,
        "currency": account.currency,
        "normal_balance": account.normal_balance,
        "lock_version": account.lock_version,
        "balances_minor": dict(sorted(per_currency.items())),
        "as_of": _utcnow().isoformat(),
    }


def get_transaction(
    session: Session, *, tenant_id: str, transaction_id: str
) -> dict[str, Any]:
    """Read one transaction with its entries (tenant-scoped)."""
    transaction = (
        session.query(LedgerTransaction)
        .filter(
            LedgerTransaction.tenant_id == str(tenant_id or "").strip(),
            LedgerTransaction.id == transaction_id,
        )
        .one_or_none()
    )
    if transaction is None:
        raise LedgerNotFoundError(f"ledger transaction {transaction_id!r} not found")
    entries = (
        session.query(LedgerEntry)
        .filter(
            LedgerEntry.tenant_id == transaction.tenant_id,
            LedgerEntry.ledger_transaction_id == transaction.id,
        )
        .order_by(LedgerEntry.created_at.asc(), LedgerEntry.id.asc())
        .all()
    )
    return {
        "id": transaction.id,
        "tenant_id": transaction.tenant_id,
        "ledger_id": transaction.ledger_id,
        "external_id": transaction.external_id,
        "description": transaction.description,
        "status": transaction.status,
        "effective_at": transaction.effective_at.isoformat() if transaction.effective_at else None,
        "posted_at": transaction.posted_at.isoformat() if transaction.posted_at else None,
        "metadata": dict(transaction.ledger_metadata or {}),
        "entries": [
            {
                "id": entry.id,
                "account_id": entry.ledger_account_id,
                "direction": entry.direction,
                "amount_minor": int(entry.amount_minor),
                "currency": entry.currency,
                "memo": entry.memo,
            }
            for entry in entries
        ],
    }

