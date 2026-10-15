"""Settlement services: calculation, status, reconciliation, and reporting.

Financial-safety principles enforced here (see PesaGuard rules 7-9, 22-23, 26):

- All money is ``Decimal`` end-to-end; no floats, no rounding surprises.
  Amounts are rounded half-up (ROUND_HALF_UP) to the currency's minor unit.
- Every query is tenant-scoped. A settlement can only be read or mutated within
  the tenant that owns it; cross-tenant access raises ``TenantIsolationError``.
- Calculation is idempotent per ``(tenant_id, settlement_key, currency)``: recomputing an
  existing settlement recomputes totals in place rather than creating a new row,
  so retried or replayed jobs cannot double-count.
- State transitions are validated by an explicit, auditable state machine; illegal
  transitions raise ``SettlementStateError`` and are recorded as security events.
- Reconciliation compares the derived net against a caller-supplied expected net
  and records an auditable ``variance_amount``; it does not independently verify
  a provider statement or prove fund movement.
"""

from __future__ import annotations

import logging
from decimal import Decimal
from datetime import datetime, timezone
from typing import Any, Dict, Iterable, List, Optional

from sqlalchemy import select
from sqlalchemy.dialects.postgresql import insert as postgresql_insert
from sqlalchemy.dialects.sqlite import insert as sqlite_insert
from sqlalchemy.orm import Session

from metrics import record_security_event
from models import Settlement, SettlementAuditEvent, SettlementLine
from normalization import (
    DEFAULT_CURRENCY,
    CurrencyMismatchError,
    NormalizationError,
    format_money,
    normalize_currency,
    parse_decimal,
    quantize_money,
    currency_quantum,
)

logger = logging.getLogger("pesaguard.settlement")

class SettlementError(Exception):
    """Base class for settlement failures."""


class TenantIsolationError(SettlementError):
    """Raised when a caller attempts to access another tenant's settlement."""


class SettlementNotFoundError(SettlementError):
    """Raised when a settlement does not exist for the given tenant/key."""


class SettlementStateError(SettlementError):
    """Raised when an illegal settlement state transition is attempted."""


class SettlementInputError(SettlementError):
    """Raised when a settlement line cannot safely be classified or included."""


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


def quantize(amount: Decimal, currency: str = DEFAULT_CURRENCY) -> Decimal:
    """Round a derived settlement amount to its currency minor unit.

    Uses the shared contract's explicit ROUND_HALF_UP rule and storage range.
    """
    return quantize_money(parse_decimal(amount), currency=currency)


def _validate_input_amount(
    amount: Any,
    currency: str,
    *,
    allow_zero: bool,
) -> Decimal:
    value = parse_decimal(amount)
    if value < 0 or (value == 0 and not allow_zero):
        raise NormalizationError("settlement input amount is outside the allowed range")
    normalized = quantize_money(value, currency=currency)
    if value != normalized:
        raise NormalizationError(
            f"settlement input precision exceeds the minor unit for {currency}"
        )
    if not allow_zero and normalized < currency_quantum(currency):
        raise NormalizationError("settlement amount must be greater than zero")
    return normalized


def _now() -> datetime:
    return datetime.now(timezone.utc)


def _record_audit_event(
    session: Session,
    settlement: Settlement,
    event_type: str,
    *,
    from_status: Optional[str] = None,
    to_status: Optional[str] = None,
    details: Optional[Dict[str, Any]] = None,
) -> None:
    session.add(
        SettlementAuditEvent(
            tenant_id=settlement.tenant_id,
            settlement_id=settlement.id,
            event_type=event_type,
            from_status=from_status,
            to_status=to_status,
            details=details or {},
        )
    )


class SettlementCalculationService:
    """Computes settlement totals from transaction lines (idempotent per key)."""

    def __init__(self, session: Session) -> None:
        self._session = session

    def _get_or_create(self, tenant_id: str, *, settlement_key: str, currency: str,
                       period_start, period_end) -> Settlement:
        currency = normalize_currency(currency)
        dialect = self._session.get_bind().dialect.name
        if dialect == "sqlite":
            insert = sqlite_insert(Settlement)
        elif dialect == "postgresql":
            insert = postgresql_insert(Settlement)
        else:
            raise SettlementError(
                f"settlement identity creation is unsupported for database dialect {dialect}"
            )

        statement = insert.values(
            tenant_id=tenant_id,
            settlement_key=settlement_key,
            currency=currency,
            period_start=period_start,
            period_end=period_end,
            status="pending",
        ).on_conflict_do_nothing(
            index_elements=["tenant_id", "settlement_key", "currency"]
        )
        self._session.execute(statement)
        settlement = self._session.execute(
            select(Settlement).where(
                Settlement.tenant_id == tenant_id,
                Settlement.settlement_key == settlement_key,
                Settlement.currency == currency,
            )
        ).scalar_one_or_none()
        if settlement is None:
            raise SettlementError("settlement creation did not produce a durable identity")
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
        currency = normalize_currency(currency)
        prepared_lines = []
        seen_transaction_ids = set()
        unsupported_entry_kinds = {
            "adjustment",
            "chargeback",
            "refund",
            "refunded",
            "reversal",
            "reversed",
        }
        for raw in lines:
            transaction_id = str(raw.get("transaction_id") or "").strip()
            if not transaction_id:
                raise SettlementInputError("settlement line requires a transaction_id")
            if transaction_id in seen_transaction_ids:
                raise SettlementInputError(
                    f"duplicate transaction line {transaction_id!r} is not allowed"
                )
            seen_transaction_ids.add(transaction_id)
            for field in ("entry_type", "type", "status"):
                kind = str(raw.get(field) or "").strip().casefold()
                if kind in unsupported_entry_kinds:
                    raise SettlementInputError(
                        f"signed {kind} entries are not supported; classify for investigation"
                    )
            line_currency = normalize_currency(raw.get("currency") or currency)
            if line_currency != currency:
                raise CurrencyMismatchError(
                    f"cannot include {line_currency} settlement line in {currency} settlement"
                )
            amount = _validate_input_amount(raw.get("amount"), currency, allow_zero=False)
            fee = _validate_input_amount(raw.get("fee", "0"), currency, allow_zero=True)
            if fee > amount:
                raise NormalizationError("settlement fee cannot exceed the transaction amount")
            net = quantize(amount - fee, currency)
            prepared_lines.append((raw, transaction_id, amount, fee, net))

        gross = sum((amount for _, _, amount, _, _ in prepared_lines), Decimal("0"))
        fees = sum((fee for _, _, _, fee, _ in prepared_lines), Decimal("0"))
        gross_total = quantize(gross, currency)
        fee_total = quantize(fees, currency)
        net_total = quantize(gross - fees, currency)

        settlement = self._get_or_create(
            tenant_id, settlement_key=settlement_key, currency=currency,
            period_start=period_start, period_end=period_end,
        )

        if settlement.status not in {"pending", "calculating", "calculated"}:
            raise SettlementStateError(
                f"cannot recalculate established settlement {settlement.id} "
                f"in state {settlement.status}"
            )

        current_lines = self._session.execute(
            select(SettlementLine).where(
                SettlementLine.settlement_id == settlement.id,
                SettlementLine.tenant_id == tenant_id,
            )
        ).scalars().all()
        transaction_ids = {transaction_id for _, transaction_id, *_ in prepared_lines}
        for line in current_lines:
            if line.transaction_id not in transaction_ids:
                self._session.delete(line)

        for raw, transaction_id, amount, fee, net in prepared_lines:
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
            line.net = net
            line.status = raw.get("status", "included")

        settlement.transaction_count = len(prepared_lines)
        settlement.gross_amount = gross_total
        settlement.fee_amount = fee_total
        settlement.net_amount = net_total
        settlement.computed_at = _now()

        status_service = SettlementStatusService(self._session)
        if settlement.status == "pending":
            status_service.transition(tenant_id, settlement.id, "calculating")
        if settlement.status == "calculating":
            status_service.transition(tenant_id, settlement.id, "calculated")

        _record_audit_event(
            self._session,
            settlement,
            "calculated",
            from_status="calculated",
            to_status="calculated",
            details={
                "currency": currency,
                "transaction_count": len(prepared_lines),
                "gross_amount": format_money(gross_total, currency),
                "fee_amount": format_money(fee_total, currency),
                "net_amount": format_money(net_total, currency),
                "lines": [
                    {
                        "transaction_id": transaction_id,
                        "source_amount": str(raw.get("amount")),
                        "source_fee": str(raw.get("fee", "0")),
                        "amount": format_money(amount, currency),
                        "fee": format_money(fee, currency),
                        "net": format_money(net, currency),
                        "provider": raw.get("provider"),
                        "trans_id": raw.get("trans_id"),
                        "status": raw.get("status", "included"),
                    }
                    for raw, transaction_id, amount, fee, net in prepared_lines
                ],
            },
        )
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
            logger.warning(
                "settlement.status.transition_rejected tenant=%s settlement=%s from=%s to=%s",
                tenant_id,
                settlement_id,
                settlement.status,
                target,
            )
            # An illegal transition is a security-relevant event: record it through
            # the shared telemetry counter (Rule #40) before refusing the change, so
            # operators can alert on repeated attempts rather than only seeing a log
            # line at INFO. The transition itself is still rejected (Rule #8/#26).
            record_security_event()
            _record_audit_event(
                self._session,
                settlement,
                "transition_rejected",
                from_status=settlement.status,
                to_status=target,
                details={"reason": "illegal_transition"},
            )
            self._session.flush()
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
    """Compares derived settlement net against a caller-supplied expected net."""

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
        """Record a supplied expected net and derive an auditable variance.

        ``variance_amount = expected_net - net_amount``. A zero variance moves the
        settlement to ``reconciled``; any non-zero variance is preserved and the
        settlement is left for investigation (``disputed``) rather than silently
        overwritten. The expected net is not independently verified against a
        provider statement.
        """
        settlement = self._session.execute(
            select(Settlement).where(
                Settlement.id == settlement_id,
                Settlement.tenant_id == tenant_id,
            )
        ).scalar_one_or_none()
        if settlement is None:
            raise SettlementNotFoundError(f"settlement {settlement_id} not found for tenant")

        expected_net = _validate_input_amount(
            expected_net, settlement.currency, allow_zero=True
        )
        net = quantize(Decimal(settlement.net_amount or 0), settlement.currency)
        variance = quantize(expected_net - net, settlement.currency)

        settlement.expected_net_amount = expected_net
        settlement.variance_amount = variance
        settlement.updated_at = _now()
        initial_status = settlement.status

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

        _record_audit_event(
            self._session,
            settlement,
            "reconciled",
            from_status=initial_status,
            to_status=settlement.status,
            details={
                "currency": settlement.currency,
                "expected_net_amount": format_money(expected_net, settlement.currency),
                "calculated_net_amount": format_money(net, settlement.currency),
                "variance_amount": format_money(variance, settlement.currency),
                "provider_statement_reference_supplied": False,
                "fund_movement_verified": False,
            },
        )
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
            "settlement_scope": "internal_calculation_only",
            "expected_net_source": "caller_supplied",
            "provider_statement_verified": False,
            "fund_movement_verified": False,
            "tenant_id": s.tenant_id,
            "settlement_key": s.settlement_key,
            "currency": s.currency,
            "status": s.status,
            "period_start": s.period_start.isoformat() if s.period_start else None,
            "period_end": s.period_end.isoformat() if s.period_end else None,
            "transaction_count": s.transaction_count,
            "gross_amount": format_money(s.gross_amount or 0, s.currency),
            "fee_amount": format_money(s.fee_amount or 0, s.currency),
            "net_amount": format_money(s.net_amount or 0, s.currency),
            "expected_net_amount": format_money(s.expected_net_amount, s.currency) if s.expected_net_amount is not None else None,
            "variance_amount": format_money(s.variance_amount, s.currency) if s.variance_amount is not None else None,
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
        """Finalize the internal record; this does not execute or attest a transfer."""
        return self.status.transition(tenant_id, settlement_id, "settled")

    def get(self, tenant_id: str, settlement_id: str) -> Dict[str, Any]:
        return self.reporting.get(tenant_id, settlement_id)

    def list_for_tenant(self, tenant_id: str, *, status: Optional[str] = None,
                        limit: int = 100, offset: int = 0) -> List[Dict[str, Any]]:
        return self.reporting.list_for_tenant(tenant_id, status=status, limit=limit, offset=offset)
