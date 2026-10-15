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
from sqlalchemy.orm.exc import StaleDataError
from sqlalchemy.orm import sessionmaker

from models import Base, Settlement, SettlementAuditEvent, SettlementLine
from normalization import CurrencyMismatchError, NormalizationError
from settlement import (
    SettlementCalculationService,
    SettlementInputError,
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


def test_recalculation_replaces_lines_removed_from_current_source_snapshot(tmp_path):
    Session = _new_session(tmp_path)
    with Session() as session:
        service = SettlementService(session)
        service.calculate(
            "tenant-a",
            settlement_key="refresh-lines",
            currency="KES",
            period_start=PERIOD_START,
            period_end=PERIOD_END,
            lines=[
                {"transaction_id": "keep", "amount": "10.00"},
                {"transaction_id": "remove", "amount": "20.00"},
            ],
        )
        refreshed = service.calculate(
            "tenant-a",
            settlement_key="refresh-lines",
            currency="KES",
            period_start=PERIOD_START,
            period_end=PERIOD_END,
            lines=[{"transaction_id": "keep", "amount": "10.00"}],
        )
        assert refreshed.transaction_count == 1
        assert refreshed.gross_amount == Decimal("10.00")
        assert {line.transaction_id for line in session.query(SettlementLine).all()} == {"keep"}


def test_calculation_uses_currency_scoped_identity_and_minor_units(tmp_path):
    Session = _new_session(tmp_path)
    with Session() as session:
        calc = SettlementCalculationService(session)
        kes = calc.calculate(
            "tenant-a",
            settlement_key="shared-key",
            currency="KES",
            period_start=PERIOD_START,
            period_end=PERIOD_END,
            lines=[{"transaction_id": "kes-1", "amount": "1.00", "fee": "0.01"}],
        )
        bhd = calc.calculate(
            "tenant-a",
            settlement_key="shared-key",
            currency="BHD",
            period_start=PERIOD_START,
            period_end=PERIOD_END,
            lines=[
                {
                    "transaction_id": "bhd-1",
                    "amount": "1.234",
                    "fee": "0.001",
                    "currency": "BHD",
                }
            ],
        )

        assert kes.currency == "KES"
        assert kes.net_amount == Decimal("0.99")
        assert bhd.currency == "BHD"
        assert bhd.net_amount == Decimal("1.233")
        assert session.query(Settlement).count() == 2


def test_invalid_or_mixed_currency_lines_do_not_create_partial_settlement(tmp_path):
    Session = _new_session(tmp_path)
    with Session() as session:
        calc = SettlementCalculationService(session)
        for line, error in (
            ({"transaction_id": "over-precise", "amount": "1.001"}, NormalizationError),
            ({"transaction_id": "negative", "amount": "-1.00"}, NormalizationError),
            ({"transaction_id": "negative-fee", "amount": "1.00", "fee": "-0.01"}, NormalizationError),
            (
                {
                    "transaction_id": "mixed-currency",
                    "amount": "1.00",
                    "currency": "USD",
                },
                CurrencyMismatchError,
            ),
        ):
            with pytest.raises(error):
                calc.calculate(
                    "tenant-a",
                    settlement_key=f"invalid-{line['transaction_id']}",
                    currency="KES",
                    period_start=PERIOD_START,
                    period_end=PERIOD_END,
                    lines=[line],
                )
        assert session.query(Settlement).count() == 0


@pytest.mark.parametrize(
    "line",
    [
        {"transaction_id": "adjustment", "amount": "1.00", "status": "adjustment"},
        {"transaction_id": "reversal", "amount": "1.00", "status": "reversal"},
        {"transaction_id": "refund", "amount": "1.00", "status": "refund"},
    ],
)
def test_adjustments_and_reversals_are_rejected_for_investigation(tmp_path, line):
    Session = _new_session(tmp_path)
    with Session() as session:
        with pytest.raises(SettlementInputError, match="not supported"):
            SettlementService(session).calculate(
                "tenant-a",
                settlement_key="unsupported-entry",
                currency="KES",
                period_start=PERIOD_START,
                period_end=PERIOD_END,
                lines=[line],
            )
        assert session.query(Settlement).count() == 0


def test_duplicate_transaction_lines_are_rejected_before_aggregation(tmp_path):
    Session = _new_session(tmp_path)
    with Session() as session:
        duplicate = [
            {"transaction_id": "tx-duplicate", "amount": "10.00"},
            {"transaction_id": "tx-duplicate", "amount": "10.00"},
        ]
        with pytest.raises(SettlementInputError, match="duplicate transaction"):
            SettlementService(session).calculate(
                "tenant-a",
                settlement_key="duplicate-lines",
                currency="KES",
                period_start=PERIOD_START,
                period_end=PERIOD_END,
                lines=duplicate,
            )
        assert session.query(Settlement).count() == 0
        assert session.query(SettlementLine).count() == 0


def test_aggregation_overflow_is_rejected_without_persisting_partial_totals(tmp_path):
    Session = _new_session(tmp_path)
    too_large_total = "9999999999999999.99"
    with Session() as session:
        with pytest.raises(NormalizationError, match="supported transaction precision"):
            SettlementService(session).calculate(
                "tenant-a",
                settlement_key="overflow",
                currency="KES",
                period_start=PERIOD_START,
                period_end=PERIOD_END,
                lines=[
                    {"transaction_id": "large-1", "amount": too_large_total},
                    {"transaction_id": "large-2", "amount": too_large_total},
                ],
            )
        assert session.query(Settlement).count() == 0
        assert session.query(SettlementLine).count() == 0


def test_calculation_history_is_persisted_as_immutable_input_snapshots(tmp_path):
    Session = _new_session(tmp_path)
    with Session() as session:
        svc = SettlementService(session)
        settlement = svc.calculate(
            "tenant-a",
            settlement_key="history",
            currency="KES",
            period_start=PERIOD_START,
            period_end=PERIOD_END,
            lines=[{"transaction_id": "tx-history", "amount": "10.00", "fee": "1.00"}],
        )
        session.commit()

        svc.calculate(
            "tenant-a",
            settlement_key="history",
            currency="KES",
            period_start=PERIOD_START,
            period_end=PERIOD_END,
            lines=[{"transaction_id": "tx-history", "amount": "20.00", "fee": "2.00"}],
        )
        session.commit()

        events = (
            session.query(SettlementAuditEvent)
            .filter_by(settlement_id=settlement.id, event_type="calculated")
            .order_by(SettlementAuditEvent.created_at, SettlementAuditEvent.id)
            .all()
        )
        assert len(events) == 2
        assert events[0].details["lines"][0]["amount"] == "10.00"
        assert events[1].details["lines"][0]["amount"] == "20.00"
        assert events[0].details["net_amount"] == "9.00"
        assert events[1].details["net_amount"] == "18.00"


def test_settlement_calculation_and_audit_roll_back_together(tmp_path, monkeypatch):
    from sqlalchemy import event

    engine = create_engine(f"sqlite:///{tmp_path / 'settlement-rollback.db'}")
    Base.metadata.create_all(engine)
    Session = sessionmaker(bind=engine)

    def fail_audit_insert(connection, cursor, statement, parameters, context, executemany):
        if statement.lstrip().upper().startswith("INSERT INTO SETTLEMENT_AUDIT_EVENTS"):
            raise RuntimeError("simulated audit storage outage")

    event.listen(engine, "before_cursor_execute", fail_audit_insert)
    try:
        with Session() as session:
            with pytest.raises(RuntimeError, match="audit storage outage"):
                SettlementService(session).calculate(
                    "tenant-a",
                    settlement_key="atomic",
                    currency="KES",
                    period_start=PERIOD_START,
                    period_end=PERIOD_END,
                    lines=[{"transaction_id": "tx-atomic", "amount": "10.00"}],
                )
            session.rollback()
            assert session.query(Settlement).count() == 0
            assert session.query(SettlementLine).count() == 0
            assert session.query(SettlementAuditEvent).count() == 0
    finally:
        event.remove(engine, "before_cursor_execute", fail_audit_insert)
        engine.dispose()


def test_concurrent_settlement_updates_reject_stale_writer(tmp_path):
    Session = _new_session(tmp_path)
    with Session() as session:
        settlement = SettlementService(session).calculate(
            "tenant-a",
            settlement_key="concurrent",
            currency="KES",
            period_start=PERIOD_START,
            period_end=PERIOD_END,
            lines=_lines(),
        )
        settlement_id = settlement.id
        session.commit()

    first = Session(expire_on_commit=False)
    stale = Session(expire_on_commit=False)
    try:
        first_record = first.query(Settlement).filter_by(id=settlement_id).one()
        stale_record = stale.query(Settlement).filter_by(id=settlement_id).one()
        first.commit()
        stale.commit()

        first_record.status = "disputed"
        first.commit()

        stale_record.status = "disputed"
        with pytest.raises(StaleDataError):
            stale.commit()
        stale.rollback()
        assert first.query(Settlement).filter_by(id=settlement_id).one().status == "disputed"
    finally:
        first.close()
        stale.close()


def test_security_metric_failure_does_not_apply_an_illegal_transition(tmp_path, monkeypatch):
    import settlement

    def fail_recording():
        raise RuntimeError("security metric unavailable")

    monkeypatch.setattr(settlement, "record_security_event", fail_recording)
    Session = _new_session(tmp_path)
    with Session() as session:
        settlement_record = SettlementService(session).calculate(
            "tenant-a",
            settlement_key="metric-failure",
            currency="KES",
            period_start=PERIOD_START,
            period_end=PERIOD_END,
            lines=_lines(),
        )
        with pytest.raises(RuntimeError, match="security metric unavailable"):
            SettlementStatusService(session).transition(
                "tenant-a", settlement_record.id, "settled"
            )
        assert settlement_record.status == "calculated"


def test_recalculation_cannot_overwrite_reconciled_financial_outcome(tmp_path):
    Session = _new_session(tmp_path)
    with Session() as session:
        svc = SettlementService(session)
        settlement = svc.calculate(
            "tenant-a",
            settlement_key="established",
            currency="KES",
            period_start=PERIOD_START,
            period_end=PERIOD_END,
            lines=[{"transaction_id": "tx-original", "amount": "10.00"}],
        )
        svc.reconcile("tenant-a", settlement.id, expected_net=Decimal("10.00"))

        with pytest.raises(SettlementStateError, match="cannot recalculate established"):
            svc.calculate(
                "tenant-a",
                settlement_key="established",
                currency="KES",
                period_start=PERIOD_START,
                period_end=PERIOD_END,
                lines=[{"transaction_id": "tx-original", "amount": "20.00"}],
            )
        assert settlement.net_amount == Decimal("10.00")
        assert session.query(SettlementLine).one().amount == Decimal("10.00")


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
        assert svc.get("tenant-a", s.id)["settlement_scope"] == "internal_calculation_only"


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


# --- Security: observability of illegal transitions (Rule #40/#47) ----------


def test_illegal_transition_records_a_security_event(tmp_path, monkeypatch, caplog):
    """The module docstring promises illegal transitions are recorded as security
    events (Rule #40). This pins that contract: an illegal transition must record
    exactly one security event through the shared telemetry counter and still be
    rejected (Rule #8/#26). A legal transition must record none."""
    import settlement

    calls = []
    monkeypatch.setattr(settlement, "record_security_event", lambda: calls.append(1))

    Session = _new_session(tmp_path)
    with Session() as session:
        svc = SettlementService(session)
        s = svc.calculate("tenant-a", settlement_key="k", currency="KES",
                          period_start=PERIOD_START, period_end=PERIOD_END, lines=_lines())
        # calculated -> settled is illegal (must reconcile first).
        with pytest.raises(SettlementStateError):
            SettlementStatusService(session).transition("tenant-a", s.id, "settled")
        assert calls == [1]  # exactly one security event for the illegal attempt
        assert "settlement.status.transition_rejected" in caplog.text
        rejected = (
            session.query(SettlementAuditEvent)
            .filter_by(settlement_id=s.id, event_type="transition_rejected")
            .one()
        )
        assert rejected.details["reason"] == "illegal_transition"

        # A legal transition records no security event.
        svc.reconcile("tenant-a", s.id, expected_net=Decimal("1727.25"))
        assert calls == [1]


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
        assert a[0]["settlement_scope"] == "internal_calculation_only"
        assert a[0]["expected_net_source"] == "caller_supplied"
        assert a[0]["provider_statement_verified"] is False
        assert a[0]["fund_movement_verified"] is False
