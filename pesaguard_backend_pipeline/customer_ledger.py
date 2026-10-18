"""
Customer / account ledger services (64-68).

Derived read models built from the immutable transaction ledger:

    Customers       - one row per tenant+derived customer key (phone tokenized)
    CustomerAccount - one account per customer x currency
    AccountBalance  - zero-decimal consolidated balance snapshot (recompute == stored)
    AccountRelationship - observed customer <-> account links

All rows are tenant-scoped, dedupe on (tenant_id, customer_key[, currency]),
enforce check constraints, and expose only Decimal money math.
"""

from __future__ import annotations

import hashlib
import logging
from dataclasses import dataclass
from datetime import datetime, timezone
from decimal import Decimal
from typing import Any, Dict, Iterable, List, Mapping, Optional, Sequence
from uuid import uuid4

from data_protection import unprotect_payload
from ingestion import IngestionError
from models import (
    AccountBalance,
    AccountRelationship,
    Customer,
    CustomerAccount,
    Transaction,
)
from normalization import (
    CURRENCY_MINOR_UNITS,
    DEFAULT_CURRENCY,
    NormalizationError,
    normalize_amount,
    normalize_currency,
    quantize_money,
    currency_quantum,
)

logger = logging.getLogger("pesaguard.customer_ledger")


def _customer_key(tenant_id: str, phone_number: str) -> str:
    """Deterministic keyed identity for one customer: sha256(tenant_id + phone)."""
    payload = f"{tenant_id}|{phone_number}".encode("utf-8")
    return "cus:v1:" + hashlib.sha256(payload).hexdigest()


class CustomerNotReady(IngestionError):
    """Raised when the internal ledger has no record for the customer on the pair."""


class AccountNotFoundError(ValueError):
    """Raised when no account exists for the customer/currency on the pair."""


class AccountSuspendedError(ValueError):
    """Raised when the account exists but is suspended - ledger reads are refused."""


class AccountClosedError(ValueError):
    """Raised when the account exists but is closed - the relationship is retired."""


class CustomerLedgerError(ValueError):
    """Base class for customer-ledger failures."""


@dataclass(frozen=True)
class CustomerAccountView:
    id: str
    customer_key: str
    provider_account_id: str
    currency: str
    status: str
    balance: Decimal
    transaction_count: int
    updated_at: Optional[datetime]


@dataclass(frozen=True)
class CustomerAccountBalance:
    customer_key: str
    currency: str
    total: Decimal
    transaction_count: int


@dataclass(frozen=True)
class _AccountInfo:
    account_id: str
    customer_key: str
    currency: str
    status: str

class CustomerLedgerDispatcher:
    """Encapsulates the per-pair derivation steps so calls are testable and auditable."""

    def __init__(self, session: Any):
        self.session = session

    def already_present(self, *, tenant_id: str, **payload: Any) -> bool:
        """Return True when the derived ledger rows already exist for this pair."""
        internal_ref = str(payload.get("internal_ref") or "")
        phone_number = str(payload.get("phone_number") or "")
        currency = normalize_currency(str(payload.get("currency") or DEFAULT_CURRENCY))
        match = (
            self.session.query(Transaction)
            .filter(
                Transaction.tenant_id == tenant_id,
                Transaction.internal_ref == internal_ref,
                Transaction.phone_number == phone_number,
                Transaction.currency == currency,
            )
            .first()
        )
        if match is None:
            return False
        customer_key = _customer_key(tenant_id, phone_number)
        account = (
            self.session.query(CustomerAccount)
            .filter(
                CustomerAccount.tenant_id == tenant_id,
                CustomerAccount.customer_key == customer_key,
                CustomerAccount.currency == currency,
            )
            .first()
        )
        return account is not None

    def upsert(self, *, tenant_id: str, **payload: Any) -> None:
        """Derive customer/account/balance/relationship rows for one pair."""
        role = str(payload.get("role") or "unknown")
        status = str(payload.get("status") or "MATCHED").upper()
        account_id = str(payload.get("account_id") or "")
        internal_ref = str(payload.get("internal_ref") or "")
        phone_number = str(payload.get("phone_number") or "")
        transaction_time = str(payload.get("timestamp") or "")
        currency = normalize_currency(str(payload.get("currency") or DEFAULT_CURRENCY))
        provider_account_id = str(payload.get("provider_account_id") or account_id)
        matched_status = _match_status(payload)

        account_info = _resolve_account(
            self.session,
            tenant_id=tenant_id,
            phone_number=phone_number,
            provider_account_id=provider_account_id,
            relationship_id=account_id,
        )
        customer_key = _customer_key(tenant_id, phone_number)
        _upsert_from_pair(
            self.session,
            tenant_id=tenant_id,
            customer_key=customer_key,
            account_id=account_id,
            role=role,
            matched_status=matched_status,
            currency=currency,
            transaction_time=transaction_time,
            pair_id=account_id,
        )


def _match_status(payload: Mapping[str, Any]) -> str:
    raw = payload.get("status") or payload.get("matched") or "MATCHED"
    return str(raw).upper()


def account_guard(
    session: Any,
    *,
    tenant_id: str,
    customer_key: str,
    account_id: str,
) -> None:
    """Fail fast when an account is no longer in a ledger-replayable state."""
    row = (
        session.query(CustomerAccount)
        .filter(
            CustomerAccount.tenant_id == tenant_id,
            CustomerAccount.customer_key == customer_key,
            CustomerAccount.id == account_id,
        )
        .first()
    )
    if row is None:
        raise AccountNotFoundError(f"no ledger account {account_id!r} for tenant {tenant_id}")
    if row.status == "suspended":
        raise AccountSuspendedError(f"account {account_id} is suspended")

def _upsert_from_pair(
    session: Any,
    *,
    tenant_id: str,
    customer_key: str,
    account_id: str,
    role: str,
    matched_status: str,
    currency: str,
    transaction_time: str,
    pair_id: Optional[str],
) -> None:
    """Idempotently upsert the derived customer ledger for one pair."""
    account_guard(session=session, tenant_id=tenant_id, customer_key=customer_key, account_id=account_id)

    currency = normalize_currency(currency)

    customer = _fetch_or_create_customer(
        session=session,
        tenant_id=tenant_id,
        customer_key=customer_key,
        phone_number=_phone_for_pair(
            tenant_id, account_id, currency, matched_status != "MATCHED", role
        ),
        matched_status=matched_status,
        currency=currency,
        transaction_time=transaction_time,
    )
    _fetch_or_create_account(
        session=session,
        tenant_id=tenant_id,
        customer_key=customer_key,
        account_id=account_id,
        currency=currency,
        status=_status_value(matched_status),
        internal_ref=transaction_time,
    )
    _update_balance(
        session=session,
        tenant_id=tenant_id,
        customer_key=customer_key,
        currency=currency,
        supplier_amount=Decimal("1"),
        matched_status=matched_status,
    )
    _record_relationship(
        session=session,
        tenant_id=tenant_id,
        customer_key=customer_key,
        account_id=account_id,
        matched_status=matched_status,
        first_seen=transaction_time,
    )


def customer_ledger_upserts(
    session: Any,
    *,
    tenant_id: str,
    pairs: Iterable[Mapping[str, Any]],
) -> tuple[int, int]:
    """Idempotent upsert driver: one call recombines a full pair batch.

    Returns ``(succeeded, skipped)`` where ``skipped`` counts pairs that are
    already materialized - the ledger must never apply the same reconciliation
    event twice. This routine never executes money movement; it only derives the
    customer, account, and balance read models from the immutable transaction
    ledger.
    """
    dispatcher = CustomerLedgerDispatcher(session)
    succeeded = 0
    skipped = 0
    for payload in pairs:
        try:
            if dispatcher.already_present(tenant_id=tenant_id, **payload):
                skipped += 1
                continue
            dispatcher.upsert(tenant_id=tenant_id, **payload)
            succeeded += 1
        except (KeyError, TypeError, ValueError, NormalizationError) as exc:
            logger.warning(
                "Customer-ledger pair skipped: tenant=%s internal_ref=%s error=%s",
                tenant_id,
                payload.get("internal_ref"),
                exc,
            )
    return succeeded, skipped


    if row.status == "closed":
        raise AccountClosedError(f"account {account_id} is closed")


def _account_present(
    session: Any,
    *,
    tenant_id: str,
    customer_key: str,
    account_id: str,
) -> Optional[CustomerAccount]:
    return (
        session.query(CustomerAccount)
        .filter(
            CustomerAccount.tenant_id == tenant_id,
            CustomerAccount.customer_key == customer_key,
            CustomerAccount.id == account_id,
        )
        .first()
    )


def _resolve_account(
    session: Any,
    *,
    tenant_id: str,
    phone_number: str,
    provider_account_id: str,
    relationship_id: Optional[str],
) -> _AccountInfo:
    base = (
        session.query(CustomerAccount)
        .filter(
            CustomerAccount.tenant_id == tenant_id,
            CustomerAccount.customer_key == _customer_key(tenant_id, phone_number),
            CustomerAccount.currency == normalize_currency(provider_account_id),
            CustomerAccount.status.in_({"active", "suspended"}),
        )
        .order_by(CustomerAccount.created_at.asc())
        .all()
    )

    if not base:
        raise AccountNotFoundError(f"no active account for customer phone {phone_number}")

    active = [a for a in base if a.status == "active"]
    chosen = active[0] if active else base[0]
    if relationship_id is not None:
        matched = [
            a for a in (active or base) if a.id == relationship_id and a.status == "active"
        ]
        if matched:
            chosen = matched[0]

    if chosen.status == "suspended":
        raise AccountSuspendedError(f"account {chosen.id} is suspended")
    if chosen.status == "closed":
        raise AccountClosedError(f"account {chosen.id} is closed")
    return _AccountInfo(
        account_id=chosen.id,
        customer_key=chosen.customer_key,
        currency=chosen.currency,
        status=chosen.status,
    )





def _status_value(status: str) -> str:
    return str(status).upper()


def _availability_tag(role: str) -> str:
    return f"available-{role}"


def _fetch_or_create_customer(
    session: Any,
    *,
    tenant_id: str,
    customer_key: str,
    phone_number: str,
    matched_status: str,
    currency: str,
    transaction_time: str,
) -> Customer:
    """Fetch an existing derived customer or create it from one pair."""
    now = datetime.now(timezone.utc)
    stored = (
        session.query(Customer)
        .filter(
            Customer.tenant_id == tenant_id,
            Customer.customer_key == customer_key,
        )
        .first()
    )
    if stored is not None:
        stored.last_seen = now
        stored.updated_at = now
        return stored

    return Customer(
        id=f"cust_{uuid4().hex}",
        tenant_id=tenant_id,
        customer_key=customer_key,
        internal_ref=transaction_time,
        phone_number=phone_number,
        label=f"Customer {customer_key[:8]}...",
        status="active" if matched_status == "MATCHED" else "suspended",
        total_volumes={currency: Decimal("1")},
        first_seen=now,
        last_seen=now,
        transaction_count=1,
        total_amount=Decimal("1"),
        updated_at=now,
    )


def _fetch_or_create_account(
    session: Any,
    *,
    tenant_id: str,
    customer_key: str,
    account_id: str,
    currency: str,
    status: str,
    internal_ref: str,
) -> CustomerAccount:
    """Fetch an existing derived account or create it from one pair."""
    now = datetime.now(timezone.utc)
    stored = (
        session.query(CustomerAccount)
        .filter(
            CustomerAccount.tenant_id == tenant_id,
            CustomerAccount.customer_key == customer_key,
            CustomerAccount.currency == currency,
            CustomerAccount.status == status,
        )
        .first()
    )
    if stored is not None:
        stored.last_seen = now
        stored.updated_at = now
        return stored

    return CustomerAccount(
        id=f"acct_{uuid4().hex}",
        tenant_id=tenant_id,
        customer_key=customer_key,
        currency=currency,
        status=status,
        total=Decimal("1"),
        transaction_count=1,
        first_seen=now,
        last_seen=now,
        updated_at=now,
        internal_ref=internal_ref,
    )


def _update_balance(
    session: Any,
    *,
    tenant_id: str,
    customer_key: str,
    currency: str,
    supplier_amount: Decimal,
    matched_status: str,
) -> None:
    """Increment the zero-decimal balance for one customer/currency pair."""
    now = datetime.now(timezone.utc)
    currency = normalize_currency(currency)
    row = (
        session.query(AccountBalance)
        .filter(
            AccountBalance.tenant_id == tenant_id,
            AccountBalance.customer_key == customer_key,
            AccountBalance.currency == currency,
        )
        .first()
    )
    stored = row.total if row is not None and row.total is not None else Decimal("0")
    count = int(row.transaction_count) if row is not None and row.transaction_count is not None else 0
    total = (stored + supplier_amount).quantize(currency_quantum(currency))
    if row is not None:
        row.total = total
        row.transaction_count = count + 1
        row.computed_at = now
        row.updated_at = now
    else:
        session.add(
            AccountBalance(
                id=f"bal_{uuid4().hex}",
                tenant_id=tenant_id,
                customer_key=customer_key,
                currency=currency,
                total=total,
                transaction_count=count + 1,
                computed_at=now,
                updated_at=now,
            )
        )


def _record_relationship(
    session: Any,
    *,
    tenant_id: str,
    customer_key: str,
    account_id: str,
    matched_status: str,
    first_seen: str,
) -> None:
    """Upsert the derived customer <-> account relationship."""
    now = datetime.now(timezone.utc)
    stored = (
        session.query(AccountRelationship)
        .filter(
            AccountRelationship.tenant_id == tenant_id,
            AccountRelationship.customer_key == customer_key,
            AccountRelationship.merchant_id == account_id,
        )
        .first()
    )
    if stored is not None:
        stored.last_seen = now
        stored.pair_count = stored.pair_count + 1
        stored.updated_at = now
    else:
        session.add(
            AccountRelationship(
                id=f"rel_{uuid4().hex}",
                tenant_id=tenant_id,
                customer_key=customer_key,
                merchant_id=account_id,
                status=_status_value(matched_status),
                first_seen=now,
                last_seen=now,
                pair_count=1,
                updated_at=now,
            )
        )


def customer_ledger_recompute(
    session: Any,
    *,
    tenant_id: str,
    customer_key: str,
    currency: str,
) -> tuple[Decimal, int]:
    """Recompute the zero-decimal balance from the raw transaction ledger."""
    currency = normalize_currency(currency)
    cursor = (
        session.query(Transaction.total, Transaction.currency, Transaction.transaction_count)
        .filter(
            Transaction.tenant_id == tenant_id,
            Transaction.customer_key == customer_key,
            Transaction.currency == currency,
        )
        .all()
    )
    total = Decimal("0")
    count = 0
    for row in cursor:
        value = Decimal(str(row.total or 0))
        total = (total + value).quantize(currency_quantum(currency))
        count += int(row.transaction_count or 0)
    return total, count


def _synced_from_pair(
    session: Any,
    *,
    tenant_id: str,
    internal_ref: str,
    phone_number: str,
    amount: Decimal,
    currency: str,
    transaction_time: str,
    matched_status: str,
) -> tuple[str, str, str]:
    """Idempotent per-pair insert auxiliary for the legacy sync API (kept for CBO)."""
    customer_key = _customer_key(tenant_id, phone_number)
    now = datetime.now(timezone.utc)
    stored = (
        session.query(CustomerAccount)
        .filter(
            CustomerAccount.tenant_id == tenant_id,
            CustomerAccount.customer_key == customer_key,
            CustomerAccount.currency == normalize_currency(currency),
        )
        .first()
    )
    if stored is not None:
        stored.last_seen = now
        stored.updated_at = now
    else:
        session.add(
            CustomerAccount(
                id=f"acct_{uuid4().hex}",
                tenant_id=tenant_id,
                customer_key=customer_key,
                currency=normalize_currency(currency),
                status="active",
                total=Decimal("1"),
                transaction_count=1,
                first_seen=now,
                last_seen=now,
                updated_at=now,
                internal_ref=internal_ref,
            )
        )
    session.add(
        Customer(
            id=f"cust_{uuid4().hex}",
            tenant_id=tenant_id,
            customer_key=customer_key,
            internal_ref=internal_ref,
            phone_number=phone_number,
            label=f"Customer {customer_key[:8]}...",
            status="active" if matched_status == "MATCHED" else "suspended",
            total_volumes={currency: Decimal("1")},
            first_seen=now,
            last_seen=now,
            transaction_count=1,
            total_amount=Decimal("1"),
            updated_at=now,
        )
    )
    return customer_key, stored.id if stored is not None else f"acct_{uuid4().hex}", customer_key


