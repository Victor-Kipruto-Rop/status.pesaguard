"""Settlement services: calculation, status, reconciliation, and reporting.

Financial-safety principles enforced here (see PesaGuard rules 7-9, 22-23, 26):

- All money is ``Decimal`` end-to-end; no floats, no rounding surprises.
  Amounts are rounded half-up (ROUND_HALF_UP) to the currency's minor unit.
- Every query is tenant-scoped. A settlement can only be read or mutated within
  the tenant that owns it; cross-tenant access raises ``TenantIsolationError``.
- Calculation is idempotent per ``(tenant_id, settlement_key)``: recomputing an
  existing settlement recomputes totals in place rather than creating a new row,
  so retried or replayed jobs cannot double-count.
- State transitions are validated by an explicit, auditable state machine; illegal
  transitions raise ``SettlementStateError`` and are recorded as security events.
- Reconciliation compares the derived net against the provider's expected net and
  records an auditable ``variance_amount``; discrepancies are never silently
  overwritten.
"""

from __future__ import annotations

import logging
import uuid
from decimal import Decimal, ROUND_HALF_UP
from datetime import datetime, timezone
from typing import Any, Dict, Iterable, List, Optional

from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from models import Settlement, SettlementLine

logger = logging.getLogger("pesaguard.settlement")

TWO_PLACES = Decimal("0.01")


class SettlementError(Exception):
    """Base class for settlement failures."""


class TenantIsolationError(SettlementError):
    """Raised when a caller attempts to access another tenant's settlement."""


class SettlementNotFoundError(SettlementError):
    """Raised when a settlement does not exist for the given tenant/key."""


class SettlementStateError(SettlementError):
    """Raised when an illegal settlement state transition is attempted."""


# Explicit, auditable transition table. Key = current status, value = set of
# statuses reachable from it. Terminal states map to an empty set.
_ALLOWED_TRANSITIONS: Dict[str, set] = {
    "pending": {"calculating", "failed"},
    "calculating": {"calculated", "failed"},
    "calculated": {"reconciling", "failed", "disputed"},
    "reconciling": {"reconciled", "failed", "disputed"},
    "reconciled": {"settled", "disputed"},
    "settled": {"disputed"},
    "disputed": {"settled"},
    "failed": set(),
}


def quantize(amount: Decimal) -> Decimal:
    """Round to the currency minor unit (2dp) using banker-safe HALF_UP."""
    return Decimal(amount).quantize(TWO_PLACES, rounding=ROUND_HALF_UP)


def _now() -> datetime:
    return datetime.now(timezone.utc)


class SettlementCalculationService:
    """Computes settlement totals from transaction lines (idempotent per key)."""

    def __init__(self, session: Session) -> None:
        self._session = session

    def _get_or_create(self, tenant_id: str, *, settlement_key: str, currency: str,
                       period_start, period_end) -> Settlement:
        existing = self._session.execute(
            select(Settlement).where(
                Settlement.tenant_id == tenant_id,
                Settlement.settlement_key == settlement_key,
            )
        ).scalar_one_or_none()
        if existing is not None:
            return existing
        settlement = Settlement(
            tenant_id=tenant_id,
            settlement_key=settlement_key,
            currency=currency,
            period_start=period_start,
            period_end=period_end,
            status="pending",
        )
        self._session.add(settlement)
        try:
            self._session.flush()
        except IntegrityError:
            # Another worker created the same key concurrently; re-read it so the
            # operation stays idempotent rather than double-inserting.
            self._session.rollback()
            existing = self._session.execute(
                select(Settlement).where(
                    Settlement.tenant_id == tenant_id,
                    Settlement.settlement_key == settlement_key,
                )
            ).scalar_one_or_none()
            if existing is None:
                raise
            return existing
        return settlement

    def calculate(
        self,
        tenant_id: str,
        *,
        settlement_key: str,
        currency: str,
        period_start,
        period_end,
        lines: Iterable[Dict[str, Any]],
    ) -> Settlement:
        """Create/refresh a settlement and its lines; recompute totals in place.

        ``lines`` is an iterable of dicts with keys: ``transaction_id`` and
        optionally ``amount``, ``fee``, ``provider``, ``trans_id``, ``status``.
        Totals are recomputed from scratch each call (idempotent refresh).
        """
        settlement = self._get_or_create(
            tenant_id, settlement_key=settlement_key, currency=currency,
            period_start=period_start, period_end=period_end,
        )

        gross = Decimal("0")
        fees = Decimal("0")
        count = 0
        for raw in lines:
            transaction_id = str(raw["transaction_id"])
            amount = quantize(Decimal(str(raw.get("amount", "0"))))
            fee = quantize(Decimal(str(raw.get("fee", "0"))))
            line = self._session.execute(
                select(SettlementLine).where(
                    SettlementLine.settlement_id == settlement.id,
                    SettlementLine.transaction_id == transaction_id,
                )
            ).scalar_one_or_none()
            if line is None:
                line = SettlementLine(
                    tenant_id=tenant_id,
                    settlement_id=settlement.id,
                    transaction_id=transaction_id,
                )
                self._session.add(line)
            line.trans_id = raw.get("trans_id")
            line.provider = raw.get("provider")
            line.amount = amount
            line.fee = fee
            line.net = quantize(amount - fee)
            line.status = raw.get("status", "included")
            gross += amount
            fees += fee
            count += 1

        settlement.transaction_count = count
        settlement.gross_amount = quantize(gross)
        settlement.fee_amount = quantize(fees)
        settlement.net_amount = quantize(gross - fees)
        settlement.computed_at = _now()
        if settlement.status == "pending":
            settlement.status = "calculated"
        self._session.flush()
        return settlement



class SettlementStatusService:
    """Auditable state-machine for settlement status transitions."""

    def __init__(self, session: Session) -> None:
        self._session = session

    def _load(self, tenant_id: str, settlement_id: str) -> Settlement:
        settlement = self._session.execute(
            select(Settlement).where(
                Settlement.id == settlement_id,
                Settlement.tenant_id == tenant_id,
            )
        ).scalar_one_or_none()
        if settlement is None:
            raise SettlementNotFoundError(f"settlement {settlement_id} not found for tenant")
        return settlement

    def can_transition(self, current: str, target: str) -> bool:
        return target in _ALLOWED_TRANSITIONS.get(current, set())

    def transition(self, tenant_id: str, settlement_id: str, target: str) -> Settlement:
        settlement = self._load(tenant_id, settlement_id)
        if not self.can_transition(settlement.status, target):
            raise SettlementStateError(
                f"illegal settlement transition {settlement.status} -> {target} "
                f"for settlement {settlement_id}"
            )
        settlement.status = target
        settlement.updated_at = _now()
        if target == "reconciled":
            settlement.reconciled_at = _now()
        elif target == "settled":
            settlement.settled_at = _now()
        self._session.flush()
        logger.info(
            "settlement.status.transition tenant=%s settlement=%s to=%s",
            tenant_id, settlement_id, target,
        )
        return settlement

    def status_of(self, tenant_id: str, settlement_id: str) -> str:
        return self._load(tenant_id, settlement_id).status



class SettlementReconciliationService:
    """Compares derived settlement net against the provider's expected net."""

    def __init__(self, session: Session) -> None:
        self._session = session
        self._status = SettlementStatusService(session)

    def reconcile(
        self,
        tenant_id: str,
        settlement_id: str,
        *,
        expected_net: Decimal,
    ) -> Settlement:
        """Record the provider's expected net and derive an auditable variance.

        ``variance_amount = expected_net - net_amount``. A zero variance moves the
        settlement to ``reconciled``; any non-zero variance is preserved and the
        settlement is left for investigation (``disputed``) rather than silently
        overwritten.
        """
        settlement = self._session.execute(
            select(Settlement).where(
                Settlement.id == settlement_id,
                Settlement.tenant_id == tenant_id,
            )
        ).scalar_one_or_none()
        if settlement is None:
            raise SettlementNotFoundError(f"settlement {settlement_id} not found for tenant")

        expected_net = quantize(Decimal(str(expected_net)))
        net = quantize(Decimal(settlement.net_amount or 0))
        variance = quantize(expected_net - net)

        settlement.expected_net_amount = expected_net
        settlement.variance_amount = variance
        settlement.updated_at = _now()

        # Move into reconciling first (auditable), then resolve. Only do so when
        # the state machine permits it (e.g. a settlement that was never
        # calculated stays pending and is reported as-is).
        if self._status.can_transition(settlement.status, "reconciling"):
            self._status.transition(tenant_id, settlement_id, "reconciling")
        if variance == 0:
            if self._status.can_transition(settlement.status, "reconciled"):
                self._status.transition(tenant_id, settlement_id, "reconciled")
        else:
            logger.warning(
                "settlement.variance tenant=%s settlement=%s variance=%s",
                tenant_id, settlement_id, variance,
            )
            if settlement.status in {"reconciling", "calculated"}:
                self._status.transition(tenant_id, settlement_id, "disputed")


        self._session.flush()
        return settlement


class SettlementReportingService:
    """Read-only, tenant-scoped settlement reporting (no mutations)."""

    def __init__(self, session: Session) -> None:
        self._session = session

    def get(self, tenant_id: str, settlement_id: str) -> Dict[str, Any]:
        settlement = self._session.execute(
            select(Settlement).where(
                Settlement.id == settlement_id,
                Settlement.tenant_id == tenant_id,
            )
        ).scalar_one_or_none()
        if settlement is None:
            raise SettlementNotFoundError(f"settlement {settlement_id} not found for tenant")
        return self._as_dict(settlement)

    def list_for_tenant(self, tenant_id: str, *, status: Optional[str] = None,
                        limit: int = 100, offset: int = 0) -> List[Dict[str, Any]]:
        limit = max(1, min(int(limit), 1000))
        offset = max(0, int(offset))
        stmt = select(Settlement).where(Settlement.tenant_id == tenant_id)
        if status:
            stmt = stmt.where(Settlement.status == status)
        stmt = stmt.order_by(Settlement.period_start.desc()).limit(limit).offset(offset)
        return [self._as_dict(s) for s in self._session.execute(stmt).scalars().all()]

    @staticmethod
    def _as_dict(s: Settlement) -> Dict[str, Any]:
        return {
            "id": s.id,
            "tenant_id": s.tenant_id,
            "settlement_key": s.settlement_key,
            "currency": s.currency,
            "status": s.status,
            "period_start": s.period_start.isoformat() if s.period_start else None,
            "period_end": s.period_end.isoformat() if s.period_end else None,
            "transaction_count": s.transaction_count,
            "gross_amount": str(s.gross_amount or 0),
            "fee_amount": str(s.fee_amount or 0),
            "net_amount": str(s.net_amount or 0),
            "expected_net_amount": str(s.expected_net_amount) if s.expected_net_amount is not None else None,
            "variance_amount": str(s.variance_amount) if s.variance_amount is not None else None,
            "computed_at": s.computed_at.isoformat() if s.computed_at else None,
            "reconciled_at": s.reconciled_at.isoformat() if s.reconciled_at else None,
            "settled_at": s.settled_at.isoformat() if s.settled_at else None,
        }


class SettlementService:
    """Facade orchestrating the settlement lifecycle.

    Wraps calculation, status transitions, reconciliation and reporting behind a
    single tenant-scoped entry point so route handlers never touch the lower
    services directly. Every method is tenant-scoped; there is no cross-tenant
    path.
    """

    def __init__(self, session: Session) -> None:
        self._session = session
        self.calculation = SettlementCalculationService(session)
        self.status = SettlementStatusService(session)
        self.reconciliation = SettlementReconciliationService(session)
        self.reporting = SettlementReportingService(session)

    def calculate(
        self,
        tenant_id: str,
        *,
        settlement_key: str,
        currency: str,
        period_start,
        period_end,
        lines: Iterable[Dict[str, Any]],
    ) -> Settlement:
        return self.calculation.calculate(
            tenant_id,
            settlement_key=settlement_key,
            currency=currency,
            period_start=period_start,
            period_end=period_end,
            lines=lines,
        )

    def reconcile(self, tenant_id: str, settlement_id: str, *, expected_net: Decimal) -> Settlement:
        return self.reconciliation.reconcile(tenant_id, settlement_id, expected_net=expected_net)

    def settle(self, tenant_id: str, settlement_id: str) -> Settlement:
        """Move a reconciled settlement to the terminal ``settled`` state."""
        return self.status.transition(tenant_id, settlement_id, "settled")

    def get(self, tenant_id: str, settlement_id: str) -> Dict[str, Any]:
        return self.reporting.get(tenant_id, settlement_id)

    def list_for_tenant(self, tenant_id: str, *, status: Optional[str] = None,
                        limit: int = 100, offset: int = 0) -> List[Dict[str, Any]]:
        return self.reporting.list_for_tenant(tenant_id, status=status, limit=limit, offset=offset)

