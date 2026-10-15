"""Auto-ledgering: post balanced double-entry entries for a settled transaction.

Decoupled from the reconciliation mutation path on purpose (Rule #7/#8): the
caller invokes this AFTER a transaction reaches a settled state, so a ledger
bug can never corrupt reconciliation or idempotency state. The service is
idempotent per (tenant, transaction) via the ledger's external_id, so retries
and replays post at most one balanced transaction (Rule #9).

Entry model (configurable account codes):
    debit  <incoming_account>   (asset increases: cash/receivable)
    credit <revenue_account>    (income recognised)

Amount is the transaction's minor-unit integer, exactly as stored; currency is
taken from the transaction so mixed currencies can never be summed (Rule #7).
"""

from __future__ import annotations

import logging
import os
from typing import Any, Dict, Optional

from sqlalchemy.orm import Session

from double_entry_ledger import (
    LedgerEntryInput,
    LedgerError,
    post_transaction,
    to_minor_units,
)
from models import Ledger, ProcessedTransaction

logger = logging.getLogger("pesaguard.auto_ledger")

AUTO_LEDGER_LEDGER_SLUG = os.getenv("PESAGUARD_AUTO_LEDGER_LEDGER_SLUG", "settlement")
AUTO_LEDGER_INCOMING_ACCOUNT = os.getenv("PESAGUARD_AUTO_LEDGER_INCOMING_ACCOUNT", "cash")
AUTO_LEDGER_REVENUE_ACCOUNT = os.getenv("PESAGUARD_AUTO_LEDGER_REVENUE_ACCOUNT", "revenue")


def auto_ledger_settled_transaction(
    session: Session,
    *,
    tenant_id: str,
    provider_account_id: str,
    daraja_trans_id: str,
    amount: Any,
    currency: str,
    ledger_id: Optional[str] = None,
) -> Dict[str, Any]:
    """Post one balanced ledger transaction for a settled payment.

    Returns a dict describing the outcome; never raises on ledger-domain
    errors so a misconfigured ledger cannot fail the caller's settlement.
    """
    tenant_id = str(tenant_id or "").strip()
    if not tenant_id:
        return {"status": "skipped", "reason": "tenant_required"}

    if ledger_id is not None:
        ledger = (
            session.query(Ledger)
            .filter(Ledger.tenant_id == tenant_id, Ledger.id == ledger_id)
            .one_or_none()
        )
    else:
        ledger = (
            session.query(Ledger)
            .filter(Ledger.tenant_id == tenant_id, Ledger.slug == AUTO_LEDGER_LEDGER_SLUG)
            .one_or_none()
        )
    if ledger is None:
        return {"status": "skipped", "reason": "ledger_not_configured"}

    try:
        amount_minor = to_minor_units(amount, currency)
    except LedgerError as exc:
        logger.warning("Auto-ledger skipped: tenant=%s trans=%s error=%s", tenant_id, daraja_trans_id, exc)
        return {"status": "skipped", "reason": "invalid_amount", "detail": str(exc)}

    entries = [
        LedgerEntryInput(
            account_code=AUTO_LEDGER_INCOMING_ACCOUNT,
            direction="debit",
            amount_minor=amount_minor,
            currency=currency,
            memo=f"settlement {daraja_trans_id}",
        ),
        LedgerEntryInput(
            account_code=AUTO_LEDGER_REVENUE_ACCOUNT,
            direction="credit",
            amount_minor=amount_minor,
            currency=currency,
            memo=f"settlement {daraja_trans_id}",
        ),
    ]
    external_id = f"auto:{provider_account_id}:{daraja_trans_id}"
    try:
        txn = post_transaction(
            session,
            tenant_id=tenant_id,
            ledger_id=ledger.id,
            external_id=external_id,
            entries=entries,
            description=f"Auto-ledgered settlement {daraja_trans_id}",
            metadata={"source": "auto_ledger", "provider_account_id": provider_account_id},
        )
        session.commit()
        logger.info(
            "Auto-ledgered settlement: tenant=%s trans=%s ledger_tx=%s",
            tenant_id, daraja_trans_id, txn.id,
        )
        return {"status": "posted", "ledger_transaction_id": txn.id, "external_id": external_id}
    except LedgerError as exc:
        session.rollback()
        logger.warning(
            "Auto-ledger failed: tenant=%s trans=%s error=%s", tenant_id, daraja_trans_id, exc
        )
        return {"status": "failed", "reason": str(exc)}
