"""Tests for settlement services: calculation, status, reconciliation, reporting.

Coverage matrix (Design -> Implement -> Unit -> Integration -> Security):
- Unit:      money math (Decimal, half-up), idempotency, totals, per-line nets.
- Integration: full lifecycle across calculate -> reconcile -> settle using a
               real SQLite database and the models.
- Security:  tenant isolation (no cross-tenant read/mutate), state-machine
               rejects illegal transitions, reconciliation variance is auditable.
"""

from datetime import datetime, timezone
from decimal import Decimal

import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from models import Base, Settlement, SettlementLine
from settlement import (
    SettlementCalculationService,
    SettlementNotFoundError,
    SettlementReconciliationService,
    SettlementReportingService,
    SettlementService,
    SettlementStateError,
    SettlementStatusService,
    quantize,
)


PERIOD_START = datetime(2026, 1, 1, tzinfo=timezone.utc)
PERIOD_END = datetime(2026, 1, 31, tzinfo=timezone.utc)


def _lines():
    return [
        {"transaction_id": "tx_1", "amount": "1000.00", "fee": "15.00", "provider": "mpesa"},
        {"transaction_id": "tx_2", "amount": "500.50", "fee": "7.50", "provider": "mpesa"},
        {"transaction_id": "tx_3", "amount": "249.25", "fee": "0.00", "provider": "airtel"},
    ]


def _new_session(tmp_path):
    engine = create_engine(f"sqlite:///{tmp_path / 'settlement.db'}")
    Base.metadata.create_all(engine)
    return sessionmaker(bind=engine)


# --- Unit: money math ---------------------------------------------------------

def test_quantize_is_half_up_two_places():
    assert quantize(Decimal("10.005")) == Decimal("10.01")
    assert quantize(Decimal("10.004")) == Decimal("10.00")
    assert quantize(Decimal("0.005")) == Decimal("0.01")


def test_calculate_sums_totals_and_line_nets(tmp_path):
    Session = _new_session(tmp_path)
    with Session() as session:
        calc = SettlementCalculationService(session)
        s = calc.calculate(
            "tenant-a",
            settlement_key="2026-01-KES",
            currency="KES",
            period_start=PERIOD_START,
            period_end=PERIOD_END,
            lines=_lines(),
        )
        assert s.transaction_count == 3
        assert s.gross_amount == Decimal("1749.75")
        assert s.fee_amount == Decimal("22.50")
        assert s.net_amount == Decimal("1727.25")
        assert s.status == "calculated"
        nets = {ln.transaction_id: ln.net for ln in session.query(SettlementLine).all()}
        assert nets == {
            "tx_1": Decimal("985.00"),
            "tx_2": Decimal("493.00"),
            "tx_3": Decimal("249.25"),
        }


# --- Integration: idempotency + lifecycle ------------------------------------

def test_calculate_is_idempotent_per_key(tmp_path):
    Session = _new_session(tmp_path)
    with Session() as session:
        calc = SettlementCalculationService(session)
        calc.calculate("tenant-a", settlement_key="k", currency="KES",
                       period_start=PERIOD_START, period_end=PERIOD_END, lines=_lines())
        calc.calculate("tenant-a", settlement_key="k", currency="KES",
                       period_start=PERIOD_START, period_end=PERIOD_END, lines=_lines())
        assert session.query(Settlement).count() == 1  # no duplicate rows
        assert session.query(SettlementLine).count() == 3  # no duplicate lines


def test_full_lifecycle_calculate_reconcile_settle(tmp_path):
    Session = _new_session(tmp_path)
    with Session() as session:
        svc = SettlementService(session)
        s = svc.calculate("tenant-a", settlement_key="k", currency="KES",
                          period_start=PERIOD_START, period_end=PERIOD_END, lines=_lines())
        s2 = svc.reconcile("tenant-a", s.id, expected_net=Decimal("1727.25"))
        assert s2.status == "reconciled"
        assert s2.variance_amount == Decimal("0.00")
        s3 = svc.settle("tenant-a", s.id)
        assert s3.status == "settled"
        assert s3.settled_at is not None


def test_variance_marks_disputed_not_silently_overwritten(tmp_path):
    Session = _new_session(tmp_path)
    with Session() as session:
        svc = SettlementService(session)
        s = svc.calculate("tenant-a", settlement_key="k", currency="KES",
                          period_start=PERIOD_START, period_end=PERIOD_END, lines=_lines())
        s2 = svc.reconcile("tenant-a", s.id, expected_net=Decimal("1500.00"))
        assert s2.variance_amount == Decimal("-227.25")
        assert s2.status == "disputed"
        assert s2.expected_net_amount == Decimal("1500.00")
        assert s2.net_amount == Decimal("1727.25")  # original preserved


# --- Security: tenant isolation ----------------------------------------------

def test_cross_tenant_read_is_denied(tmp_path):
    Session = _new_session(tmp_path)
    with Session() as session:
        svc = SettlementService(session)
        s = svc.calculate("tenant-a", settlement_key="k", currency="KES",
                          period_start=PERIOD_START, period_end=PERIOD_END, lines=_lines())
        with pytest.raises(SettlementNotFoundError):
            svc.reporting.get("tenant-b", s.id)
        with pytest.raises(SettlementNotFoundError):
            SettlementStatusService(session).transition("tenant-b", s.id, "settled")


def test_same_key_isolated_per_tenant(tmp_path):
    Session = _new_session(tmp_path)
    with Session() as session:
        calc = SettlementCalculationService(session)
        calc.calculate("tenant-a", settlement_key="shared", currency="KES",
                       period_start=PERIOD_START, period_end=PERIOD_END, lines=_lines())
        calc.calculate("tenant-b", settlement_key="shared", currency="KES",
                       period_start=PERIOD_START, period_end=PERIOD_END, lines=_lines())
        assert session.query(Settlement).count() == 2


# --- Security: state machine --------------------------------------------------

def test_illegal_transition_rejected(tmp_path):
    Session = _new_session(tmp_path)
    with Session() as session:
        svc = SettlementService(session)
        s = svc.calculate("tenant-a", settlement_key="k", currency="KES",
                          period_start=PERIOD_START, period_end=PERIOD_END, lines=_lines())
        # calculated -> settled is illegal (must reconcile first)
        with pytest.raises(SettlementStateError):
            SettlementStatusService(session).transition("tenant-a", s.id, "settled")


def test_terminal_states_reject_further_moves(tmp_path):
    Session = _new_session(tmp_path)
    with Session() as session:
        svc = SettlementService(session)
        s = svc.calculate("tenant-a", settlement_key="k", currency="KES",
                          period_start=PERIOD_START, period_end=PERIOD_END, lines=_lines())
        svc.reconcile("tenant-a", s.id, expected_net=Decimal("1727.25"))
        svc.settle("tenant-a", s.id)
        assert svc.status.status_of("tenant-a", s.id) == "settled"
        with pytest.raises(SettlementStateError):
            SettlementStatusService(session).transition("tenant-a", s.id, "reconciled")


# --- Reporting ----------------------------------------------------------------

def test_reporting_list_scoped_and_serializable(tmp_path):
    Session = _new_session(tmp_path)
    with Session() as session:
        svc = SettlementService(session)
        svc.calculate("tenant-a", settlement_key="k", currency="KES",
                      period_start=PERIOD_START, period_end=PERIOD_END, lines=_lines())
        svc.calculate("tenant-b", settlement_key="k", currency="KES",
                      period_start=PERIOD_START, period_end=PERIOD_END, lines=_lines())
        a = svc.list_for_tenant("tenant-a")
        b = svc.list_for_tenant("tenant-b")
        assert len(a) == 1 and len(b) == 1
        assert a[0]["tenant_id"] == "tenant-a"
        assert a[0]["net_amount"] == "1727.25"  # Decimal serialised as string

